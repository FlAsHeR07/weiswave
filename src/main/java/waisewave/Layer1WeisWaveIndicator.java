package waisewave;

import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentListener;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.*;
import velox.api.layer1.layers.strategies.interfaces.*;
import velox.api.layer1.messages.GeneratedEventInfo;
import velox.api.layer1.messages.Layer1ApiUserMessageAddStrategyUpdateGenerator;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.*;
import velox.api.layer1.messages.indicators.DataStructureInterface.TreeResponseInterval;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

@Layer1Attachable
@Layer1StrategyName("Weis wave")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class Layer1WeisWaveIndicator implements
        Layer1ApiFinishable,
        Layer1ApiAdminAdapter,
        Layer1ApiInstrumentListener,
        OnlineCalculatable {

    private static class BarEvent implements CustomGeneratedEvent, DataCoordinateMarker {
        private static final long serialVersionUID = 1L;
        private long time;
        private double price, volume;
        private boolean isUpWave;
        transient int bodyWidthPx;
        private int mov;
        private int trend;

        public BarEvent(long time) {
            this.time = time;
            this.price = Double.NaN;
            this.volume = 0;
            isUpWave = true;
        }

        public BarEvent(long time, double price, double volume, boolean isUpWave, int bodyWidthPx) {
            this.time = time;
            this.price = price;
            this.volume = volume;
            this.isUpWave = isUpWave;
            this.bodyWidthPx = bodyWidthPx;
        }

        public BarEvent(BarEvent other) {
            this(other.time, other.price, other.volume, other.isUpWave, other.bodyWidthPx);
        }

        public void setMov(int mov) {
            this.mov = mov;
        }

        public void setTrend(int trend) {
            this.trend = trend;
        }

        @Override
        public long getTime() {
            return time;
        }

        @Override
        public Object clone() {
            return new BarEvent(time, price, volume, isUpWave, bodyWidthPx);
        }

        @Override
        public String toString() {
            return "BarEvent{" +
                    "time=" + time +
                    ", price=" + price +
                    ", volume=" + volume +
                    ", color=" + isUpWave +
                    ", bodyWidthPx=" + bodyWidthPx +
                    '}';
        }

        @Override
        public double getMinY() {
            return 0;
        }

        @Override
        public double getMaxY() {
            return volume;
        }

        @Override
        public double getValueY() {
            return volume;
        }

        public void update(BarEvent barEvent) {
            if (barEvent == null) {
                return;
            }

            price = barEvent.price;
            volume += barEvent.volume;
            isUpWave = barEvent.isUpWave;
        }

        public void setBodyWidthPx(int bodyWidthPx) {
            this.bodyWidthPx = bodyWidthPx;
        }

        @Override
        public Marker makeMarker(Function<Double, Integer> yDataCoordinateToPixelFunction) {

            // todo: log here to check for error

            if (Double.isNaN(price)) {
                return new Marker(1, 1, 1, new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
            }

            int volumePx = yDataCoordinateToPixelFunction.apply(volume) - yDataCoordinateToPixelFunction.apply(0.);
            BufferedImage bufferedImage = new BufferedImage(bodyWidthPx, volumePx + 1, BufferedImage.TYPE_INT_ARGB);

            Graphics2D graphics = bufferedImage.createGraphics();
            // Clear background
            graphics.setBackground(new Color(0, 0, 0, 0));
            graphics.clearRect(0, 0, bufferedImage.getWidth(), bufferedImage.getHeight());

            // Fill bar with appropriate color
            graphics.setColor(isUpWave ? Color.GREEN : Color.RED);
            graphics.fillRect(0, 0, bufferedImage.getWidth() - 1, bufferedImage.getHeight() - 1);

            graphics.dispose();

            return new Marker(volume, -(bufferedImage.getWidth() / 2), -volumePx, bufferedImage);
        }

        public void applyPips(double pips) {
            price *= pips;
        }
    }

    private static class BarsAggregator implements CustomEventAggregatble, Serializable {
        @Override
        public CustomGeneratedEvent getInitialValue(long t) {
            return new BarEvent(t);
        }

        @Override
        public void aggregateAggregationWithValue(CustomGeneratedEvent aggregation, CustomGeneratedEvent value) {
            BarEvent aggregationEvent = (BarEvent) aggregation;
            BarEvent valueEvent = (BarEvent) value;
            aggregationEvent.update(valueEvent);
        }

        @Override
        public void aggregateAggregationWithAggregation(CustomGeneratedEvent aggregation1,
                                                        CustomGeneratedEvent aggregation2) {
            BarEvent aggregationEvent1 = (BarEvent) aggregation1;
            BarEvent aggregationEvent2 = (BarEvent) aggregation2;
            aggregationEvent1.update(aggregationEvent2);
        }
    }

    public static final CustomEventAggregatble BAR_EVENTS_AGGREGATOR = new BarsAggregator();
    public static final String BARS_GENERATOR_NAME = BarEventsGenerator.class.getCanonicalName();
    private final BarEventsGenerator barEventsGenerator;
    private static final String INDICATOR_NAME_BARS_BOTTOM = "Bars: bottom panel";
    private static final String INDICATOR_LINE_COLOR_NAME = "Trade markers line";
    private static final Color INDICATOR_LINE_DEFAULT_COLOR = Color.RED;
    private static final int MAX_BODY_WIDTH = 60;
    private static final int MIN_BODY_WIDTH = 1;
    private static final long CANDLE_INTERVAL_NS = TimeUnit.MINUTES.toNanos(1);

    private final Layer1ApiProvider provider;

    private final Map<String, String> indicatorsFullNameToUserName = new HashMap<>();
    private final Map<String, String> indicatorsUserNameToFullName = new HashMap<>();

    private final Map<String, Double> pipsMap = new ConcurrentHashMap<>();

    private DataStructureInterface dataStructureInterface;

    public Layer1WeisWaveIndicator(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
        barEventsGenerator = new BarEventsGenerator();
    }

    @Override
    public void finish() {
        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                provider.sendUserMessage(new Layer1ApiUserMessageModifyIndicator(Layer1WeisWaveIndicator.class, userName, false));
            }
        }

        provider.sendUserMessage(getGeneratorMessage(false));
    }

    private Layer1ApiUserMessageModifyIndicator getUserMessageAdd(String userName, GraphType graphType) {
        return Layer1ApiUserMessageModifyIndicator.builder(Layer1WeisWaveIndicator.class, userName)
                .setIsAdd(true)
                .setGraphType(graphType)
                .setOnlineCalculatable(this)
                .setIndicatorColorScheme(new IndicatorColorScheme() {
                    @Override
                    public ColorDescription[] getColors() {
                        return new ColorDescription[] {
                                new ColorDescription(Layer1WeisWaveIndicator.class, INDICATOR_LINE_COLOR_NAME, INDICATOR_LINE_DEFAULT_COLOR, false),
                        };
                    }

                    @Override
                    public String getColorFor(Double value) {
                        return INDICATOR_LINE_COLOR_NAME;
                    }

                    @Override
                    public ColorIntervalResponse getColorIntervalsList(double valueFrom, double valueTo) {
                        return new ColorIntervalResponse(new String[] {INDICATOR_LINE_COLOR_NAME}, new double[] {});
                    }
                })
                .setIndicatorLineStyle(IndicatorLineStyle.NONE)
                .build();
    }

    @Override
    public void onUserMessage(Object data) {
        if (data.getClass() == UserMessageLayersChainCreatedTargeted.class) {
            UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
            if (message.targetClass == getClass()) {
                provider.sendUserMessage(new Layer1ApiDataInterfaceRequestMessage(dataStructureInterface -> this.dataStructureInterface = dataStructureInterface));
                addIndicator(INDICATOR_NAME_BARS_BOTTOM);
                provider.sendUserMessage(getGeneratorMessage(true));
            }
        }
    }

    @Override
    public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
        pipsMap.put(alias, instrumentInfo.pips);
    }

    @Override
    public void onInstrumentRemoved(String alias) {
    }

    @Override
    public void onInstrumentNotFound(String symbol, String exchange, String type) {
    }

    @Override
    public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
    }


    class BarEventsGenerator implements StrategyUpdateGenerator {
        private Consumer<CustomGeneratedEventAliased> consumer;
        private AtomicLong time = new AtomicLong(0);
        private Map<String, BarEvent> aliasToLastBar = new HashMap<>();
        private BarEvent previousBar = null;
        // private AtomicBoolean historyInitialized = new AtomicBoolean(false);
        private double accumulatedVolume = 0;
        private ReentrantLock lock = new ReentrantLock();

        @Override
        public void setGeneratedEventsConsumer(Consumer<CustomGeneratedEventAliased> consumer) {
            this.consumer = consumer;
        }

        @Override
        public Consumer<CustomGeneratedEventAliased> getGeneratedEventsConsumer() {
            return consumer;
        }

        @Override
        public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
        }

        @Override
        public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
            lock.lock();
            int bodyMinWidthPx = 1;
            Double pips = pipsMap.get(alias);

            if (previousBar == null) {
                previousBar = new BarEvent(time.get(), price, size,
                        true, bodyMinWidthPx);

                previousBar.setMov(1);
                previousBar.setTrend(1);
                previousBar.applyPips(pips);
                consumer.accept(new CustomGeneratedEventAliased(previousBar, alias));

            } else {
                long barStartTime = getBarStartTime(time.get());
                accumulatedVolume += size;

                if(barStartTime > CANDLE_INTERVAL_NS) {
                    BarEvent currentBar = new BarEvent(time.get(), price, accumulatedVolume, true, bodyMinWidthPx);

                    currentBar.applyPips(pips);
                    currentBar.setMov(currentBar.price > previousBar.price ? 1 : (currentBar.price < previousBar.price ? -1 : 0));
                    currentBar.setTrend((currentBar.mov != 0) && (currentBar.mov != previousBar.mov) ? currentBar.mov : previousBar.trend);
                    boolean isTrending = currentBar.mov == previousBar.mov;
                    boolean isUpWave = isTrending ? (currentBar.trend == 1) : previousBar.isUpWave;
                    double volume = isUpWave == previousBar.isUpWave ? (previousBar.volume + accumulatedVolume) : accumulatedVolume;

                    currentBar.isUpWave = isUpWave;
                    currentBar.volume = volume;

                    consumer.accept(new CustomGeneratedEventAliased(currentBar, alias));

                    previousBar = currentBar;

                    accumulatedVolume = 0;
                }
            }

            lock.unlock();
        }

        private long getBarStartTime(long time) {
            return time - previousBar.time;
        }

        @Override
        public void setTime(long time) {
            this.time.set(time);
        }

        @Override
        public void onStatus(StatusInfo statusInfo) {
        }

        @Override
        public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {
        }

        @Override
        public void onOrderExecuted(ExecutionInfo executionInfo) {
        }

        @Override
        public void onBalance(BalanceInfo balanceInfo) {
        }

        @Override
        public void onMarketMode(String alias, MarketMode marketMode) {

        }

        @Override
        public void onDepth(String alias, boolean isBid, int price, int size) {
        }

        @Override
        public void onInstrumentRemoved(String alias) {
            aliasToLastBar.remove(alias);
        }

        @Override
        public void onInstrumentNotFound(String symbol, String exchange, String type) {
        }

        @Override
        public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
        }

        @Override
        public void onUserMessage(Object data) {
        }
    }

    @Override
    public void calculateValuesInRange(String indicatorName, String indicatorAlias, long t0, long intervalWidth, int intervalsNumber,
                                       CalculatedResultListener listener) {
        Log.info("*************** calculateValuesInRange was called ***************");
        System.gc();

        List<TreeResponseInterval> result = dataStructureInterface.get(Layer1WeisWaveIndicator.class, BARS_GENERATOR_NAME, t0, intervalWidth,
                intervalsNumber, indicatorAlias, new Class[] {BarEvent.class});

        int bodyWidth = getBodyWidth(intervalWidth);

        for (int i = 1; i <= intervalsNumber; i++) {

            BarEvent value = getBarEvent(result.get(i));
            if (value != null) {
                /*
                 * IMPORTANT: don't edit value returned by interface directly. It might be
                 * cached by bookmap for performance reasons, so you'll often end up with the
                 * modified value next time you request it, but it isn't going to happen every
                 * time, so the behavior wont be predictable.
                 */
                value = new BarEvent(value);

                value.setBodyWidthPx(bodyWidth);
                value.applyPips(pipsMap.get(indicatorAlias));
                listener.provideResponse(value);
            } else {
                listener.provideResponse(Double.NaN);
            }
        }

        listener.setCompleted();
    }

    private BarEvent getBarEvent(TreeResponseInterval treeResponseInterval) {
        Object result = treeResponseInterval.events.get(BarEvent.class.toString());
        if (result != null) {
            return (BarEvent) result;
        } else {
            return null;
        }
    }

    @Override
    public OnlineValueCalculatorAdapter createOnlineValueCalculator(String indicatorName, String indicatorAlias, long time,
                                                                    Consumer<Object> listener, InvalidateInterface invalidateInterface) {

        Double pips = pipsMap.get(indicatorAlias);

        return new OnlineValueCalculatorAdapter() {

            int bodyWidth = MAX_BODY_WIDTH;

            @Override
            public void onIntervalWidth(long intervalWidth) {
                this.bodyWidth = getBodyWidth(intervalWidth);
            }

            @Override
            public void onUserMessage(Object data) {
                if (data instanceof CustomGeneratedEventAliased) {
                    CustomGeneratedEventAliased aliasedEvent = (CustomGeneratedEventAliased) data;
                    if (aliasedEvent.alias.equals(indicatorAlias) && aliasedEvent.event instanceof BarEvent) {
                        BarEvent event = (BarEvent)aliasedEvent.event;
                        if(TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis()) - event.time < TimeUnit.MINUTES.toNanos(3)) {
                            event = new BarEvent(event);
                            event.setBodyWidthPx(bodyWidth);
                            event.applyPips(pips);
                            listener.accept(event);
                        }
                    }
                }
            }
        };
    }

    private static int getBodyWidth(long intervalWidth) {
        long bodyWidth = CANDLE_INTERVAL_NS / intervalWidth;
        bodyWidth = Math.max(bodyWidth, MIN_BODY_WIDTH);
        bodyWidth = Math.min(bodyWidth, MAX_BODY_WIDTH);
        return (int) bodyWidth;

    }

    public void addIndicator(String userName) {
        Layer1ApiUserMessageModifyIndicator message = null;
        switch (userName) {
            case INDICATOR_NAME_BARS_BOTTOM:
                message = getUserMessageAdd(userName, GraphType.BOTTOM);
                break;
            default:
                Log.warn("Unknwon name for marker indicator: " + userName);
                break;
        }

        if (message != null) {
            synchronized (indicatorsFullNameToUserName) {
                indicatorsFullNameToUserName.put(message.fullName, message.userName);
                indicatorsUserNameToFullName.put(message.userName, message.fullName);
            }
            provider.sendUserMessage(message);
        }
    }

    private Layer1ApiUserMessageAddStrategyUpdateGenerator getGeneratorMessage(boolean isAdd) {
        return new Layer1ApiUserMessageAddStrategyUpdateGenerator(Layer1WeisWaveIndicator.class, BARS_GENERATOR_NAME,
                isAdd, true, true, barEventsGenerator,
                new GeneratedEventInfo[] {new GeneratedEventInfo(BarEvent.class, BarEvent.class, BAR_EVENTS_AGGREGATOR)});
    }
}