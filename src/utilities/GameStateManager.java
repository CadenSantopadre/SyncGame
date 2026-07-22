package utilities;

public class GameStateManager {

    public static final int TITLE = 0;
    public static final int LEVEL_SELECT = 1;
    public static final int IN_LEVEL = 2;
    public static final int END_LEVEL = 3;

    private int currentState = TITLE;

    private int subState = 0;

    public void setState(int newState) {
        currentState = newState;
    }

    public void setSubState(int newSubState) {
        subState = newSubState;
    }

    public int getState() {
        return currentState; 
    }

    public int getSubState() {
        return subState;
    }
}
