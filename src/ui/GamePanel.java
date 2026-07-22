package ui;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Color;
import javax.swing.JPanel;

import game.Calibration;
import game.Level1;
import game.Level2;

import java.awt.GradientPaint;
import java.awt.Graphics;
import utilities.KeyHandler;
import java.awt.BasicStroke;

import utilities.GameStateManager;

public class GamePanel extends JPanel implements Runnable { //extends means GamePanel becomes a JPanel class. Implements means it needs a publci void run()
    private Thread gameThread;
    private Graphics2D g2;
    private KeyHandler keyH = new KeyHandler(); //Keyhandler reads key inputs
    private GameStateManager stateManager;
    private TitleScreen titleScreen;
    private LevelSelectScreen levelSelectScreen;
    private Calibration calibrationScreen;
    private Level1 level1Screen;
    private Level2 level2Screen;
    private double calibrationOffsetMs = 0.0; //This is our calibration offset, which gets passed in every level

    public int screenWidth = 1280;
    public int screenHeight = 720;

    public GamePanel() {
        setPreferredSize(new Dimension(screenWidth,screenHeight)); //We have to pass a Dimension object as an arg here
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(keyH); //keyH is our listener

        //Every time we make a class that updates or draws, gotta pass it as a "new" thing here
        stateManager = new GameStateManager();
        titleScreen = new TitleScreen(this, stateManager, keyH);
        levelSelectScreen = new LevelSelectScreen(this, stateManager, keyH);
        calibrationScreen = new Calibration(this, stateManager, keyH);
        level1Screen = new Level1(this, stateManager, keyH);
        level2Screen = new Level2(this, stateManager, keyH);

        requestFocusInWindow();
    }

    public void startGameThread() {
        //This starts our game timer
        gameThread = new Thread(this);
        gameThread.start();
    }

    public void setCalibrationOffsetMs(double offsetMs) {
        //This takes offsetMs from a calibration test and updates our GamePanel's parameter
        calibrationOffsetMs = offsetMs;
    }

    public double getCalibrationOffsetMs() {
        //This is so levels can get the "global" offset
        return calibrationOffsetMs;
    }

    @Override //This tells our "implements Runnable" that we have a run method
    public void run() {
        final int FPS = 60; //Pick your FPS
        final double frameTime = 1_000_000_000.0 / FPS; //Frametime is in nanoseconds, so we do billion/FPS

        long lastTime = System.nanoTime();
        double delta = 0;

        while (true) {
            long now = System.nanoTime();
            delta += (now - lastTime) / frameTime; //Add to delta how much fo the frame time has passed (t/T)
            lastTime = now;

            while (delta >= 1) { //When t/T is T/T, then update and repaint
                update();
                repaint();
                delta--; //Tehn reset to 0
            }

            try {
                Thread.sleep(1); //We also want to stop very quickly every frame to prevent 100% CPU usage
            } catch (InterruptedException e) { //We do try in case a multi-thread error occurs
                e.printStackTrace();
            }
        }
    }

    private void update() {
        //It's good to put in what your states are here
        int state = stateManager.getState();
        /*  
            Title = 0
            Level_Select = 1
            In_Level = 2
            End_Level = 3
        */

        switch(state) { //For every state, tell each class to update accordingly
            case 0:
                titleScreen.update();
                break;
            case 1:
                levelSelectScreen.update();
                break;
            case 2:
                switch (stateManager.getSubState()) {
                    case 0:
                        calibrationScreen.update();
                        break;
                    
                    case 1:
                        level1Screen.update();
                        break;

                    case 2:
                        level2Screen.update();
                        break;
                }

        }
    }

    @Override
    public void paintComponent(Graphics g) {
        //super clears the last frame. Even if you're using fullscreen graphics, just put this in
        super.paintComponent(g);
        g2 = (Graphics2D) g; //Graphics2D is much better for mkaing a game

        int state = stateManager.getState();
        switch (state) {
            case 0:
                titleScreen.draw(g2);
                break;
            case 1:
                levelSelectScreen.draw(g2);
                break;
            case 2:
                switch (stateManager.getSubState()) {
                    case 0:
                        calibrationScreen.draw(g2);
                        break;
                    
                    case 1:
                        level1Screen.draw(g2);
                        break;

                    case 2:
                        level2Screen.draw(g2);
                        break;
                }
        }
    }
    
    public void drawGradientBox(Graphics2D g2, int x, int y, int w, int h) { //Make any "global" graphics tools in GamePanel
        GradientPaint left = new GradientPaint(x, y, Color.BLACK, x, y + h, Color.BLUE);

        GradientPaint right = new GradientPaint(x + w, y, Color.BLACK, x + w, y + h, Color.BLUE);

        g2.setPaint(left);
        g2.fillRect(x, y, w / 2, h);

        g2.setPaint(right);
        g2.fillRect(x + w / 2, y, w / 2, h);

        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(5)); 
        g2.drawRect(x, y, w, h);
    }
}