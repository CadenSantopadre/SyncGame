package ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;

import utilities.GameStateManager;
import utilities.KeyHandler;

public class LevelSelectScreen {

    private final GamePanel gp;
    private final GameStateManager state;
    private final KeyHandler keyH;

    private final String[] levels = {"Calibration", "Level 1", "Level 2", "Level 3 (Locked)"};
    private int selectedLevel = 0;

    public LevelSelectScreen(GamePanel gp, GameStateManager state, KeyHandler keyH) {
        this.gp = gp;
        this.state = state;
        this.keyH = keyH;
    }

    public void update() {
        if (keyH.upPressed) {
            selectedLevel--;
            if (selectedLevel < 0) selectedLevel = 0;
            keyH.upPressed = false;
        }

        if (keyH.downPressed) {
            selectedLevel++;
            if (selectedLevel >= levels.length) selectedLevel = levels.length - 1;
            keyH.downPressed = false;
        }

        if (keyH.enterPressed) {
            switch (selectedLevel) {
                
                case 0:
                    state.setState(GameStateManager.IN_LEVEL);
                    state.setSubState(selectedLevel);
                    break;
                case 1:
                    state.setState(GameStateManager.IN_LEVEL);
                    state.setSubState(selectedLevel);
                    break;
                case 2:
                    state.setState(GameStateManager.IN_LEVEL);
                    state.setSubState(selectedLevel);
                    break;   
            }
            
            keyH.enterPressed = false;
        }

        if (keyH.escPressed) { //Esc lets them go back
            state.setState(GameStateManager.TITLE);
            keyH.escPressed = false;
        }
    }

    public void draw(Graphics2D g2) {
        gp.drawGradientBox(g2, 0, 0, gp.screenWidth, gp.screenHeight);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 42));
        g2.drawString("Select Level", 430, 140);

        g2.setFont(new Font("Arial", Font.PLAIN, 32));
        int startY = 260;
        int spacing = 70;

        for (int i = 0; i < levels.length; i++) {
            int y = startY + i * spacing;
            String label = levels[i];

            if (selectedLevel == i) {
                g2.drawString(">", 420, y);
            }

            g2.setColor(i <= 2 ? Color.WHITE : Color.GRAY); //The ternary(?) asks if i<=2 is true, then if it is, it does the first option, else, the second
            g2.drawString(label, 470, y);
        }

        g2.setColor(Color.CYAN);
        g2.setFont(new Font("Arial", Font.PLAIN, 24));
        g2.drawString("Press Enter to play", 400, 500);
        g2.drawString("Press Escape to return to the title screen", 330, 540);
    }
}
