package com.tianyi.zhang.multiplayer.snake.elements;

import com.badlogic.gdx.Gdx;
import com.tianyi.zhang.multiplayer.snake.helpers.Constants;
import com.tianyi.zhang.multiplayer.snake.helpers.Utils;
import com.tianyi.zhang.multiplayer.snake.protobuf.generated.ClientPacket;
import com.tianyi.zhang.multiplayer.snake.protobuf.generated.ServerPacket;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ClientSnapshot extends Snapshot {
    private static final String TAG = ClientSnapshot.class.getCanonicalName();

    private final long startTimestamp;
    private final int clientId;
    private static final long SNAKE_MOVE_EVERY_NS = TimeUnit.MILLISECONDS.toNanos(Constants.MOVE_EVERY_MS);
    private static final long LAG_TOLERANCE_NS = TimeUnit.MILLISECONDS.toNanos(Constants.LAG_TOLERANCE_MS);

    private final AtomicInteger serverUpdateVersion;

    private final AtomicLong nextRenderTime;

    private final Object lock;
    /**
     * Makes up the last game step, guarded by stateLock
     */
    private final List<Snake> snakes;
    private final Foods foods;
    private long stateTime;
    private int nextInputId;
    private final List<Input> unackInputs;

    public ClientSnapshot(int clientId, ServerPacket.Update initialUpdate) {
        long currentNanoTime = Utils.getNanoTime();

        this.clientId = clientId;
        this.lock = new Object();
        this.nextInputId = 1;
        this.unackInputs = new LinkedList<Input>();
        this.stateTime = 0;
        this.serverUpdateVersion = new AtomicInteger(initialUpdate.getVersion());
        this.nextRenderTime = new AtomicLong(0);
        this.startTimestamp = currentNanoTime - initialUpdate.getTimestamp();

        Gdx.app.debug(TAG, String.format("startTimestamp: %,d", startTimestamp));

        List<ServerPacket.Update.PSnake> pSnakes = initialUpdate.getSnakesList();
        List<Snake> snakes = new ArrayList<Snake>(pSnakes.size());
        for (ServerPacket.Update.PSnake pSnake : pSnakes) {
            snakes.add(Snake.fromProtoSnake(pSnake));
        }
        this.snakes = new ArrayList<Snake>(snakes);
        this.foods = new Foods();
        this.foods.setLocations(initialUpdate.getFoodLocationsList());
    }

    /**
     *
     * @return true if a new frame should be rendered, false otherwise
     */
    @Override
    public boolean update() {
        long currentNs = Utils.getNanoTime() - startTimestamp;
        if (currentNs >= nextRenderTime.get()) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void onClientInput(int direction) {
        synchronized (lock) {
            long tmpNs = Utils.getNanoTime() - startTimestamp;
            Input input = new Input(direction, nextInputId++, tmpNs);
            Input lastInput = (unackInputs.isEmpty() ? snakes.get(clientId).getLastInput() : unackInputs.get(unackInputs.size()-1));
            if (lastInput.isValidNewInput(input)) {
                unackInputs.add(input);
            }
        }
    }

    @Override
    public void onServerUpdate(ServerPacket.Update update) {
        if (update.getVersion() > serverUpdateVersion.get()) {
            Gdx.app.debug(TAG, "Server update version " + update.getVersion() + " received.");
            Gdx.app.debug(TAG, update.toString());
            serverUpdateVersion.set(update.getVersion());
            synchronized (lock) {
                List<ServerPacket.Update.PSnake> pSnakes = update.getSnakesList();
                for (int i = 0; i < pSnakes.size(); ++i) {
                    ServerPacket.Update.PSnake pSnake = pSnakes.get(i);
                    Snake newSnake = Snake.fromProtoSnake(pSnake);
                    snakes.set(newSnake.id, newSnake);

                    stateTime = update.getTimestamp();

                    if (newSnake.id == clientId) {
                        int lastAckInputId = newSnake.getLastInput().id;
                        while (!unackInputs.isEmpty() && lastAckInputId <= unackInputs.get(0).id) {
                            unackInputs.remove(0);
                        }
                    }
                }

                List<Integer> foodLocations = update.getFoodLocationsList();
                foods.setLocations(foodLocations);
            }
        } else {
            synchronized (lock) {
                if (!unackInputs.isEmpty()) {
                    while (!unackInputs.isEmpty() && update.getTimestamp() - unackInputs.get(0).timestamp > LAG_TOLERANCE_NS * 2) {
                        unackInputs.remove(0);
                    }
                }
            }
        }
    }

    public ClientPacket.Message buildMessage() {
        ClientPacket.Message.Builder builder = ClientPacket.Message.newBuilder();
        synchronized (lock) {
            if (unackInputs.size() == 0) {
                return null;
            }

            for (com.tianyi.zhang.multiplayer.snake.elements.Input tmpInput : unackInputs) {
                builder.addInputs(tmpInput.toProtoInput());
            }
        }
        return builder.build();
    }

    @Override
    public Grid getGrid() {
        long currentTime = Utils.getNanoTime() - startTimestamp;
        int currentStep = (int) (currentTime / SNAKE_MOVE_EVERY_NS);

        List<Snake> resultSnakes;
        Foods resultFoods;
        synchronized (lock) {
            resultSnakes = new ArrayList<Snake>(snakes.size());
            for (int i = 0; i < snakes.size(); ++i) {
                resultSnakes.add(new Snake(snakes.get(i)));
            }
            resultFoods = new Foods(foods);

            int stateStep = (int) (stateTime / SNAKE_MOVE_EVERY_NS);
            int stepDiff = currentStep - stateStep;

            int inputIndex = 0;
            for (int i = 0; i <= stepDiff; ++i) {
                long upper = (i == stepDiff ? currentTime : SNAKE_MOVE_EVERY_NS * (stateStep + i + 1));
                for (int j = 0; j < resultSnakes.size(); ++j) {
                    Snake currSnake = resultSnakes.get(j);
                    if (j == clientId) {
                        Input tmpInput;
                        while (unackInputs.size() > inputIndex && (tmpInput = unackInputs.get(inputIndex)).timestamp < upper) {
                            currSnake.handleInput(tmpInput);
                            inputIndex += 1;
                        }
                    }
                    if (i != stepDiff) {
                        currSnake.forward();
                        resultFoods.consumedBy(currSnake);
                    }
                }
            }

            nextRenderTime.set(SNAKE_MOVE_EVERY_NS * (currentStep + 1));
        }
        return new Grid(Utils.getNanoTime(), resultSnakes, clientId, resultFoods);
    }
}
