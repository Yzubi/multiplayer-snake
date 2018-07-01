package com.tianyi.zhang.multiplayer.snake.elements;

import com.tianyi.zhang.multiplayer.snake.agents.messages.Packet;
import com.tianyi.zhang.multiplayer.snake.helpers.Constants;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class Snake {
    public final int id;
    private final List<Integer> coords;
    private Input lastInput;
    private boolean isDead;

    public Snake(int id, int[] coords, Input input) {
        this.id = id;
        this.coords = new LinkedList<Integer>();
        for (int i = 0; i < coords.length; ++i) {
            this.coords.add(new Integer(coords[i]));
        }
        this.lastInput = input;
        this.isDead = false;
    }

    public Snake(int id, List<Integer> coords, Input input) {
        this.id = id;
        this.coords = new LinkedList<Integer>(coords);
        this.lastInput = input;
        this.isDead = false;
    }

    public Snake(int id, int headX, int headY, int length, Input input) {
        this.id = id;
        this.lastInput = input;

        this.coords = new LinkedList<Integer>();
        int x = headX, y = headY;
        for (int i = 0; i < length; ++i) {
            coords.add(Integer.valueOf(x));
            coords.add(Integer.valueOf(y));
            switch (input.direction) {
                case Constants.LEFT:
                    x += 1;
                    break;
                case Constants.UP:
                    y -= 1;
                    break;
                case Constants.RIGHT:
                    x -= 1;
                    break;
                case Constants.DOWN:
                    y += 1;
                    break;
            }
        }
        this.isDead = false;
    }

    public static Snake fromProtoSnake(Packet.Update.PSnake pSnake) {
        Input input = Input.fromProtoInput(pSnake.getLastInput());
        return new Snake(pSnake.getId(), pSnake.getCoordsList(), input);
    }

    public Snake(Snake snake) {
        this.id = snake.id;
        this.coords = new LinkedList<Integer>(snake.coords);
        this.lastInput = snake.lastInput;
        this.isDead = snake.isDead;
    }

    public void forward() {
        if (!isDead) {
            int size = coords.size();
            coords.remove(size - 1);
            coords.remove(size - 2);
            int x0 = coords.get(0).intValue(), y0 = coords.get(1).intValue();
            switch (lastInput.direction) {
                case Constants.LEFT:
                    --x0;
                    break;
                case Constants.UP:
                    ++y0;
                    break;
                case Constants.RIGHT:
                    ++x0;
                    break;
                case Constants.DOWN:
                    --y0;
                    break;
            }
            coords.add(0, y0);
            coords.add(0, x0);
        }
    }

    public List<Integer> getCoordinates() {
        return Collections.unmodifiableList(coords);
    }

    public void die() {
        this.isDead = true;
    }

    public void handleInput(Input input) {
        if (!isDead && lastInput.isValidNewInput(input)) {
            this.lastInput = input;
        }
    }

    public Input getLastInput() {
        return lastInput;
    }

    @Override
    public String toString() {
        String str = String.format("%s snake %d, direction %d, last input ID %d, head coordinates (%d, %d).",
                isDead ? "Dead" : "Live", id, lastInput.direction, lastInput.id, coords.get(0), coords.get(1));
        return str;
    }
}
