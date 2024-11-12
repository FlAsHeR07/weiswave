package waisewave;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import velox.api.layer1.*;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.BalanceInfo;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.MarketMode;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.data.StatusInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.datastructure.events.TradeAggregationEvent;
import velox.api.layer1.layers.strategies.interfaces.CalculatedResultListener;
import velox.api.layer1.layers.strategies.interfaces.CustomEventAggregatble;
import velox.api.layer1.layers.strategies.interfaces.CustomGeneratedEvent;
import velox.api.layer1.layers.strategies.interfaces.CustomGeneratedEventAliased;
import velox.api.layer1.layers.strategies.interfaces.InvalidateInterface;
import velox.api.layer1.layers.strategies.interfaces.OnlineCalculatable;
import velox.api.layer1.layers.strategies.interfaces.OnlineValueCalculatorAdapter;
import velox.api.layer1.messages.GeneratedEventInfo;
import velox.api.layer1.messages.Layer1ApiUserMessageAddStrategyUpdateGenerator;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.DataStructureInterface;
import velox.api.layer1.messages.indicators.DataStructureInterface.TreeResponseInterval;
import velox.api.layer1.messages.indicators.IndicatorColorScheme;
import velox.api.layer1.messages.indicators.IndicatorLineStyle;
import velox.api.layer1.messages.indicators.Layer1ApiDataInterfaceRequestMessage;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.api.layer1.messages.indicators.StrategyUpdateGenerator;

@Layer1Attachable
@Layer1StrategyName("Weis wave")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class Layer1ApiWeisWaveIndicator implements
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

        // Interaction with setters
        private int mov;
        private int trend;


        public BarEvent(long time) {
            this.time = time;
            this.price = Double.NaN;
            this.volume = 0;
            isUpWave = true;
        }

        public BarEvent(long time, double price, double volume, int bodyWidthPx) {
            isUpWave = true;
            this.time = time;
            this.price = price;
            this.volume = volume;
            this.bodyWidthPx = bodyWidthPx;
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

        public int getMov() {
            return mov;
        }

        public void setMov(int mov) {
            this.mov = mov;
        }

        public int getTrend() {
            return trend;
        }

        public void setTrend(int trend) {
            this.trend = trend;
        }

        public boolean isUpWave() {
            return isUpWave;
        }

        public void setUpWave(boolean upWave) {
            isUpWave = upWave;
        }

        public void setTime(long time) {
            this.time = time;
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

        public void accumulateVolume(BarEvent barEvent) {
            if (barEvent == null) {
                return;
            }

            volume += barEvent.volume;
        }

        public void update(BarEvent barEvent) {
            if (barEvent == null) {
                return;
            }

            price = barEvent.price;
            volume += barEvent.volume;
        }

        public static double calculateVolume(TradeAggregationEvent event) {
            double totalVolume = 0;
            totalVolume += calculateMapVolume(event.askAggressorMap);
            totalVolume += calculateMapVolume(event.bidAggressorMap);
            return totalVolume;
        }

        private static double calculateMapVolume(Map<Double, Map<Integer, Integer>> aggressorMap) {
            double volume = 0;
            for (Map.Entry<Double, Map<Integer, Integer>> priceLevelEntry : aggressorMap.entrySet()) {
                Map<Integer, Integer> tradeSizes = priceLevelEntry.getValue();
                for (Map.Entry<Integer, Integer> tradeEntry : tradeSizes.entrySet()) {
                    volume += tradeEntry.getKey() * tradeEntry.getValue();
                }
            }
            return volume;
        }

        public void setBodyWidthPx(int bodyWidthPx) {
            this.bodyWidthPx = bodyWidthPx;
        }

        @Override
        public Marker makeMarker(Function<Double, Integer> yDataCoordinateToPixelFunction) {

            if (Double.isNaN(price)) {
                return new Marker(1, 1, 1, new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB));
            }

            int volumePx = Math.abs(yDataCoordinateToPixelFunction.apply(volume));
            BufferedImage bufferedImage = new BufferedImage(bodyWidthPx, volumePx + 1, BufferedImage.TYPE_INT_ARGB);
            int imageCenterX = bufferedImage.getWidth() / 2;

            Graphics2D graphics = bufferedImage.createGraphics();
            // Clear background
            graphics.setBackground(new Color(0, 0, 0, 0));
            graphics.clearRect(0, 0, bufferedImage.getWidth(), bufferedImage.getHeight());

            // Fill bar with appropriate color
            graphics.setColor(isUpWave ? Color.GREEN : Color.RED);
            graphics.fillRect(0, 0, bufferedImage.getWidth() - 1, bufferedImage.getHeight() - 1);

            graphics.dispose();

            return new Marker(volume, -(bufferedImage.getWidth() / 2),
                    yDataCoordinateToPixelFunction.apply(0.) - volumePx, bufferedImage);
        }

        /**
         * We initially compute everything in level number, like onDepth calls are
         * (prices divided by pips), but if we are going to render it on the bottom
         * panel we want to convert into price
         */
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
    private static final String INDICATOR_NAME_BARS_BOTTOM = "Bars: bottom panel";
    private static final String INDICATOR_LINE_COLOR_NAME = "Trade markers line";
    private static final Color INDICATOR_LINE_DEFAULT_COLOR = Color.RED;
    private static final Class<?>[] INTERESTING_CUSTOM_EVENTS = new Class<?>[] { BarEvent.class };

    private static final int MAX_BODY_WIDTH = 60;
    private static final int MIN_BODY_WIDTH = 1;
    private static final long CANDLE_INTERVAL_NS = TimeUnit.MINUTES.toNanos(1);

    private final Layer1ApiProvider provider;

    private final Map<String, String> indicatorsFullNameToUserName = new HashMap<>();
    private final Map<String, String> indicatorsUserNameToFullName = new HashMap<>();

    private final Map<String, Double> pipsMap = new ConcurrentHashMap<>();

    private DataStructureInterface dataStructureInterface;

    public Layer1ApiWeisWaveIndicator(Layer1ApiProvider provider) {
        this.provider = provider;
        ListenableHelper.addListeners(provider, this);
    }

    @Override
    public void finish() {
        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                provider.sendUserMessage(new Layer1ApiUserMessageModifyIndicator(Layer1ApiWeisWaveIndicator.class, userName, false));
            }
        }

        provider.sendUserMessage(getGeneratorMessage(false));
    }

    private Layer1ApiUserMessageModifyIndicator getUserMessageAdd(String userName, GraphType graphType) {
        return Layer1ApiUserMessageModifyIndicator.builder(Layer1ApiWeisWaveIndicator.class, userName)
                .setIsAdd(true)
                .setGraphType(graphType)
                .setOnlineCalculatable(this)
                .setIndicatorColorScheme(new IndicatorColorScheme() {
                    @Override
                    public ColorDescription[] getColors() {
                        return new ColorDescription[] {
                                new ColorDescription(Layer1ApiWeisWaveIndicator.class, INDICATOR_LINE_COLOR_NAME, INDICATOR_LINE_DEFAULT_COLOR, false),
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
                provider.sendUserMessage(getGeneratorMessage(true));
                provider.sendUserMessage(new Layer1ApiDataInterfaceRequestMessage(dataStructureInterface -> this.dataStructureInterface = dataStructureInterface));
                addIndicator(INDICATOR_NAME_BARS_BOTTOM);
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

    @Override
    public void calculateValuesInRange(String indicatorName, String indicatorAlias, long t0, long intervalWidth, int intervalsNumber,
                                       CalculatedResultListener listener) {
        Double pips = pipsMap.get(indicatorAlias);

        List<TreeResponseInterval> intervalResponse = dataStructureInterface.get(t0, intervalWidth, intervalsNumber, indicatorAlias,
                new DataStructureInterface.StandardEvents[] {DataStructureInterface.StandardEvents.TRADE});

        int bodyWidthPx = getBodyWidth(intervalWidth);
        TradeAggregationEvent currentTrade = (TradeAggregationEvent)intervalResponse.get(1).events.get(DataStructureInterface.StandardEvents.TRADE.toString());;
        TradeAggregationEvent anchorTrade = currentTrade;
        BarEvent previousBar = null;
        int createdBars = 0;
        int currentInterval = 2;
        double accumulatedVolume = 0;

        while (createdBars < 1 && currentInterval < intervalResponse.size()) {
            currentTrade = (TradeAggregationEvent)intervalResponse.get(currentInterval).events.get(DataStructureInterface.StandardEvents.TRADE.toString());

            if (currentTrade.time - anchorTrade.time >= CANDLE_INTERVAL_NS) {
                accumulatedVolume += BarEvent.calculateVolume(currentTrade);

                BarEvent bar = new BarEvent(currentTrade.time, currentTrade.lastPrice, accumulatedVolume,
                        true, bodyWidthPx);
                previousBar = bar;
                bar.setMov(1);
                bar.setTrend(1);
                bar.applyPips(pips);
                listener.provideResponse(bar);

                anchorTrade = currentTrade;
                accumulatedVolume = 0;
                createdBars++;
            } else {
                listener.provideResponse(new BarEvent(currentTrade.time));
                accumulatedVolume += BarEvent.calculateVolume(currentTrade);
            }

            currentInterval++;
        }

        while (currentInterval < intervalsNumber && currentInterval < intervalResponse.size()) {
            currentTrade = (TradeAggregationEvent)intervalResponse.get(currentInterval).events.get(DataStructureInterface.StandardEvents.TRADE.toString());

            if (currentTrade.time - anchorTrade.time >= CANDLE_INTERVAL_NS) {
                accumulatedVolume += BarEvent.calculateVolume(currentTrade);

                BarEvent bar = new BarEvent(currentTrade.time, currentTrade.lastPrice, accumulatedVolume,
                        true, bodyWidthPx);

                bar.applyPips(pips);
                bar.setMov(bar.price > previousBar.price ? 1 : (bar.price < previousBar.price ? -1 : 0));
                bar.setTrend((bar.mov != 0) && (bar.mov != previousBar.mov) ? bar.mov : previousBar.trend);
                boolean isTrending = bar.mov == previousBar.mov;
                boolean isUpWave = isTrending ? (bar.trend == 1) : previousBar.isUpWave;
                double volume = isUpWave == previousBar.isUpWave ? (previousBar.volume + accumulatedVolume) : accumulatedVolume;

                bar.isUpWave = isUpWave;
                bar.volume = volume;

                listener.provideResponse(bar);

                previousBar = bar;
                anchorTrade = currentTrade;
                accumulatedVolume = 0;
            } else {
                listener.provideResponse(new BarEvent(currentTrade.time));
                accumulatedVolume += BarEvent.calculateVolume(currentTrade);
            }

            currentInterval++;
        }

        // Plug cycle
        while (currentInterval <= intervalsNumber) {
            listener.provideResponse(new BarEvent(currentTrade.time));

            currentInterval++;
        }

        listener.setCompleted();

    }

    @Override
    public OnlineValueCalculatorAdapter createOnlineValueCalculator(String indicatorName, String indicatorAlias, long time,
                                                                    Consumer<Object> listener, InvalidateInterface invalidateInterface) {
        String userName = indicatorsFullNameToUserName.get(indicatorName);
        boolean isBottomChart = userName.equals(INDICATOR_NAME_BARS_BOTTOM);

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
                    if (indicatorAlias.equals(aliasedEvent.alias) && aliasedEvent.event instanceof BarEvent) {
                        BarEvent event = (BarEvent)aliasedEvent.event;
                        /**
                         * Same idea as in calculateValuesInRange - we don't want to mess up the
                         * message, but here it's for a different reason. We have a chance of changing
                         * it before or after it's stored inside bookmap, also resulting in undefined
                         * behavior.
                         */
                        event = new BarEvent(event);
                        event.setBodyWidthPx(bodyWidth);
                        if (isBottomChart) {
                            event.applyPips(pips);
                        }
                        listener.accept(event);
                    }
                }
            }
        };
    }

    private int getBodyWidth(long intervalWidth) {
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
        return new Layer1ApiUserMessageAddStrategyUpdateGenerator(Layer1ApiWeisWaveIndicator.class, INDICATOR_NAME_BARS_BOTTOM,
                isAdd, true, new StrategyUpdateGenerator() {
            private Consumer<CustomGeneratedEventAliased> consumer;

            private long time = 0;

            private Map<String, BarEvent> aliasToLastBar = new HashMap<>();

            @Override
            public void setGeneratedEventsConsumer(Consumer<CustomGeneratedEventAliased> consumer) {
                this.consumer = consumer;
            }

            @Override
            public Consumer<CustomGeneratedEventAliased> getGeneratedEventsConsumer() {
                return consumer;
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
            public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
                /*BarEvent bar = aliasToLastBar.get(alias);

                long barStartTime = getBarStartTime(time);

                if (bar == null) {
                    bar = new BarEvent(barStartTime);
                    aliasToLastBar.put(alias, bar);
                }

                if (barStartTime != bar.time) {
                    bar.setTime(time);
                    bar = new BarEvent(barStartTime, bar.close);
                    consumer.accept(new CustomGeneratedEventAliased(bar, alias));
                    aliasToLastBar.put(alias, bar);
                }

                if (size != 0) {
                    bar.update(price);
                }*/
            }

            @Override
            public void onMarketMode(String alias, MarketMode marketMode) {
            }

            @Override
            public void onDepth(String alias, boolean isBid, int price, int size) {
            }

            @Override
            public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
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

            @Override
            public void setTime(long time) {
                this.time = time;

                /*
                 * Publish finished bars. Bookmap call this method periodically even if nothing
                 * is happening at around 50ms intervals (avoid relying on exact value as it
                 * might be changed in the future).
                 */
                long barStartTime = getBarStartTime(time);
                for (Entry<String, BarEvent> entry : aliasToLastBar.entrySet()) {
                    String alias = entry.getKey();
                    BarEvent bar = entry.getValue();

                    if (barStartTime != bar.time) {
                        bar.setTime(time);
                        consumer.accept(new CustomGeneratedEventAliased(bar, alias));
                        bar = new BarEvent(barStartTime);
                        entry.setValue(bar);
                    }
                }
            }
        }, new GeneratedEventInfo[] {new GeneratedEventInfo(BarEvent.class, BarsAggregator.class, BAR_EVENTS_AGGREGATOR)});
    }

    private long getBarStartTime(long time) {
        return time - time % CANDLE_INTERVAL_NS;
    }
}

