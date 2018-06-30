package com.tianyi.zhang.multiplayer.snake.elements;

import com.badlogic.gdx.Gdx;
import com.tianyi.zhang.multiplayer.snake.agents.messages.Packet;
import com.tianyi.zhang.multiplayer.snake.helpers.Constants;
import com.tianyi.zhang.multiplayer.snake.helpers.Utils;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.tianyi.zhang.multiplayer.snake.helpers.Constants.RIGHT;

public class ClientSnapshot extends Snapshot {
    private static final String TAG = ClientSnapshot.class.getCanonicalName();

    private final long startTimestamp;
    private final int clientId;
    private static final long SNAKE_MOVE_EVERY_NS = TimeUnit.MILLISECONDS.toNanos(Constants.MOVE_EVERY_MS);
    private static final long UPDATE_AFTER_INACTIVE_NS = TimeUnit.MILLISECONDS.toNanos(Constants.UPDATE_AFTER_INACTIVE_MS);

    private final AtomicInteger serverUpdateVersion;

    private final AtomicLong nextRenderTime;

    private final Object lock;
    /**
     * Makes up the last game step, guarded by stateLock
     */
    private final List<Snake> snakes;
    private long stateTime;
    private int nextInputId;
    private final List<Input> unackInputs;

    public ClientSnapshot(int clientId, long startTimestamp, int[] snakeIds) {
        this.clientId = clientId;
        this.lock = new Object();
        this.snakes = new LinkedList<Snake>();
        this.nextInputId = 1;
        this.unackInputs = new LinkedList<Input>();
        this.stateTime = 0;
        this.serverUpdateVersion = new AtomicInteger(Integer.MIN_VALUE);
        this.nextRenderTime = new AtomicLong(0);
        this.startTimestamp = startTimestamp;

        int id = 0;
        for (int index = 0; index < snakeIds.length; ++index) {
            while (id <= snakeIds[index]) {
                this.snakes.add(new Snake(id, new int[]{3, 3, 2, 3, 1, 3, 0, 3}, new Input(RIGHT, 0, 0)));
                id += 1;
            }
        }
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
            } else {
                Gdx.app.debug(TAG, "Input " + input.id + " rejected");
            }
        }
    }

    @Override
    public void onServerUpdate(Packet.Update update) {
        if (update.getState() == Packet.Update.PState.GAME_IN_PROGRESS) {
            if (update.getVersion() > serverUpdateVersion.get()) {
                Gdx.app.debug(TAG, "Server update version " + update.getVersion() + " received.");
                Gdx.app.debug(TAG, update.toString());
                serverUpdateVersion.set(update.getVersion());
                synchronized (lock) {
                    List<Packet.Update.PSnake> pSnakes = update.getSnakesList();
                    for (int i = 0; i < pSnakes.size(); ++i) {
                        Packet.Update.PSnake pSnake = pSnakes.get(i);
                        int tmpId = pSnake.getId();
                        Packet.Update.PInput pInput = pSnake.getLastInput();
                        Input newInput = new Input(pInput.getDirection(), pInput.getId(), pInput.getTimestamp());
                        Snake newSnake = new Snake(tmpId, pSnake.getCoordsList(), newInput);
                        snakes.set(tmpId, newSnake);

                        stateTime = update.getTimestamp();

                        if (tmpId == clientId) {
                            int lastAckInputId = pInput.getId();
                            while (!unackInputs.isEmpty() && lastAckInputId <= unackInputs.get(0).id) {
                                unackInputs.remove(0);
                            }
                        }
                    }
                }
            } else {
                synchronized (lock) {
                    long updatedStateTime;
                    if (unackInputs.isEmpty()) {
                        updatedStateTime = update.getTimestamp();
                    } else {
                        updatedStateTime = (unackInputs.get(0).timestamp < update.getTimestamp()) ? unackInputs.get(0).timestamp : update.getTimestamp();
                    }
                    int updatedStateStep = (int) (updatedStateTime / SNAKE_MOVE_EVERY_NS);

                    int stateStep = (int) (stateTime / SNAKE_MOVE_EVERY_NS);
                    int stepDiff = updatedStateStep - stateStep;

                    if (stepDiff >= UPDATE_AFTER_INACTIVE_NS / SNAKE_MOVE_EVERY_NS) {
                        for (int i = 0; i < stepDiff; ++i) {
                            for (int j = 0; j < snakes.size(); ++j) {
                                snakes.get(j).forward();
                            }
                        }

                        stateTime = updatedStateTime;
                    }
                }
            }
        }
    }

    public Input[] getNewInputs() {
        synchronized (lock) {
            Input[] inputs = new Input[unackInputs.size()];
            inputs = unackInputs.toArray(inputs);
            return inputs;
        }
    }

    @Override
    public Snake[] getSnakes() {
        long currentTime = Utils.getNanoTime() - startTimestamp;
        int currentStep = (int) (currentTime / SNAKE_MOVE_EVERY_NS);

        synchronized (lock) {
            Snake[] resultSnakes = new Snake[snakes.size()];
            for (int i = 0; i < resultSnakes.length; ++i) {
                resultSnakes[i] = new Snake(snakes.get(i));
            }

            int stateStep = (int) (stateTime / SNAKE_MOVE_EVERY_NS);
            int stepDiff = currentStep - stateStep;

            int inputIndex = 0;
            for (int i = 0; i <= stepDiff; ++i) {
                long upper = (i == stepDiff ? currentTime : SNAKE_MOVE_EVERY_NS * (stateStep + i + 1));
                for (int j = 0; j < resultSnakes.length; ++j) {
                    if (j == clientId) {
                        // Apply inputs
                        Input tmpInput;
                        while (unackInputs.size() > inputIndex && (tmpInput = unackInputs.get(inputIndex)).timestamp < upper) {
                            resultSnakes[j].handleInput(tmpInput);
                            inputIndex += 1;
                        }
                    }
                    if (i != stepDiff) {
                        // Move snakes forward
                        resultSnakes[j].forward();
                    }
                }
            }

            nextRenderTime.set(SNAKE_MOVE_EVERY_NS * (currentStep + 1));

            return resultSnakes;
        }
    }
}