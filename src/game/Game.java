package game;

import javax.swing.JFrame;
import ui.GamePanel;

public class Game {

    public Game() {
        JFrame window = new JFrame(); //Our window will be a JFrame object
        window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); //When hit the X in the corner, close
        window.setResizable(false); //We don't want to change the size
        window.setTitle("My Game"); //Title it whatever you want

        GamePanel gamePanel = new GamePanel(); //Make a new GamePanel to render everything
        window.add(gamePanel); //Add the gamePanel to the window

        window.pack(); //sizes the window so it fits game panel's description
        window.setLocationRelativeTo(null); //Puts it on the main screen if you have two monitors
        window.setVisible(true); //Self explanatory

        gamePanel.startGameThread(); //Starts the game timer
    }
}