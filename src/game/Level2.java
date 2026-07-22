package game;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import ui.GamePanel;
import utilities.GameStateManager;
import utilities.KeyHandler;
import json.ChartLoader;
import utilities.SongChart;

public class Level2 {
    private final GamePanel gp;
    private final GameStateManager state;
    private final KeyHandler keyH;
    
    private final int circleSize = 36;
    private final int targetX = 640;
    private final int targetY = 360;
    private final int targetRadius = 36;
    
    private long startTime;
    private final int pixelsPerSecond = 400;
    private final int approachTimeMs = 1500;
    private final int hitWindowMs = 80;
    
    public int score = 0;
    public double lastOffsetMs = 0;
    private List<Note> notes;
    private String feedback = "";
    private int feedbackTimer = 0;
    private String songTitle = "Loading...";
    private List<VisualParticle> particles;
    private boolean levelStarted = false;
    
    // Configured parameters parsed from JSON
    private int bpm = 120; 
    private double songOffsetMs = 1000.0; 
    private double msPerBeat;
    
    // New variables added for custom JSON list processing
    private List<Double> chartBeats = new ArrayList<>();
    private int nextBeatIndex = 0;
    private final int lookAheadMs = 2000; 
    private boolean levelComplete = false;
    private boolean resultAccepted = false;

    public Level2(GamePanel gp, GameStateManager state, KeyHandler keyH) {
        this.gp = gp;
        this.state = state;
        this.keyH = keyH;
        notes = new ArrayList<>();
        particles = new ArrayList<>();
    }

    private void startLevel() {
        if (levelStarted) {
            return;
        }
        initNotes();
        levelStarted = true;
    }

    private void initNotes() {
        notes = new ArrayList<>();
        SongChart chart = ChartLoader.loadChart("/json/chart.json");
        
        if (chart != null) {
            this.songTitle = chart.songName != null && !chart.songName.isEmpty() ? chart.songName : "Infinite Mode";
            this.bpm = chart.bpm;
            this.songOffsetMs = chart.offsetMs;
            
            // Safe assignment of the custom list from your parsed chart object
            if (chart.beats != null) {
                this.chartBeats = chart.beats;
                this.chartBeats.sort(Double::compareTo); // Sorts chronological timestamps automatically
            }
        } else {
            this.songTitle = "Chart missing (Default Mode)";
        }

        this.msPerBeat = (60.0 / bpm) * 1000.0;
        this.nextBeatIndex = 0; // Clear the data array cursor index positions
        
        startTime = System.currentTimeMillis();
        generateBeatsUpTo(lookAheadMs); // Populate initial window stream
    }

    private void generateBeatsUpTo(double targetHorizonMs) {
        // Sequentially advance through chart list using index pointer tracking bounds
        while (nextBeatIndex < chartBeats.size()) {
            double customBeatValue = chartBeats.get(nextBeatIndex);
            double targetTimeMs = songOffsetMs + (customBeatValue * msPerBeat);
            
            if (targetTimeMs <= targetHorizonMs) {
                notes.add(new Note(customBeatValue));
                nextBeatIndex++;
            } else {
                break; // Stop parsing since subsequent entries are farther along timeline axis
            }
        }
    }

    public void update() {
        if (!levelStarted) {
            startLevel();
        }

        if (levelComplete) {
            if (keyH.enterPressed) {
                keyH.enterPressed = false;
                resultAccepted = true;
                state.setState(GameStateManager.LEVEL_SELECT);
                reset();
            } else if (keyH.escPressed) {
                keyH.escPressed = false;
                resultAccepted = false;
                state.setState(GameStateManager.LEVEL_SELECT);
                reset();
            }
            return;
        }

        double currentTime = System.currentTimeMillis() - startTime;
        
        // Feed real-time viewport delta into array stream listener logic 
        generateBeatsUpTo(currentTime + approachTimeMs + lookAheadMs);

        // 1. Handle feedback timer
        if (feedbackTimer > 0) {
            feedbackTimer--;
            if (feedbackTimer == 0) feedback = "";
        }

        // 2. ONLY flag as Miss if the note completely passed the hit window
        double calibrationOffset = gp.getCalibrationOffsetMs();
        double effectiveHitWindowMs = getEffectiveHitWindowMs(calibrationOffset);

        for (Note note : notes) {
            double targetTime = note.getTargetTimeMs(songOffsetMs, msPerBeat);
            double diff = currentTime - targetTime;
            if (!note.hit && !note.missed && diff > effectiveHitWindowMs) {
                note.missed = true;
                feedback = "Miss!";
                feedbackTimer = 30;
            }
        }

        // 3. Process key press inputs
        if (keyH.enterPressed) {
            keyH.enterPressed = false;
            Note closestNote = null;
            double closestDistance = Double.MAX_VALUE;

            for (Note note : notes) {
                double targetTime = note.getTargetTimeMs(songOffsetMs, msPerBeat);
                if (!note.hit && !note.missed) {
                    double diff = Math.abs(currentTime - targetTime);
                    if (diff < closestDistance) {
                        closestDistance = diff;
                        closestNote = note;
                    }
                }
            }

            // 4. Validate if the closest note falls within the hit window
            if (closestNote != null && closestDistance <= effectiveHitWindowMs) {
                closestNote.hit = true;
                lastOffsetMs = currentTime - closestNote.getTargetTimeMs(songOffsetMs, msPerBeat);
                double absOffset = Math.abs(lastOffsetMs);
                String timingModifier = "";
                
                if (lastOffsetMs < 0) {
                    timingModifier = " Early";
                } else if (lastOffsetMs > 0) {
                    timingModifier = " Late";
                }

                double perfectThreshold = getEffectiveThresholdMs(20, calibrationOffset);
                double greatThreshold = getEffectiveThresholdMs(45, calibrationOffset);

                if (absOffset <= perfectThreshold) {
                    feedback = "Perfect!";
                    score += 300;
                    spawnParticles(0, Color.GREEN);
                } else if (absOffset <= greatThreshold) {
                    feedback = "Great" + timingModifier;
                    score += 200;
                    spawnParticles(lastOffsetMs < 0 ? -1 : 1, Color.GREEN);
                } else {
                    feedback = "Good" + timingModifier;
                    score += 100;
                    spawnParticles(lastOffsetMs < 0 ? -1 : 1, Color.ORANGE);
                }
                feedbackTimer = 30;
            } else {
                feedback = "Miss!";
                feedbackTimer = 30;
            }
        }

        // 5. State transitions
        if (keyH.escPressed) {
            state.setState(GameStateManager.LEVEL_SELECT);
            reset();
            keyH.escPressed = false;
        }

        // Drop out stale nodes to safeguard allocations over time
        notes.removeIf(note -> (currentTime - note.getTargetTimeMs(songOffsetMs, msPerBeat)) > approachTimeMs);
        particles.removeIf(VisualParticle::update);

        checkLevelEnd(currentTime);
    }

    private double getEffectiveHitWindowMs(double calibrationOffsetMs) {
        double compensation = -calibrationOffsetMs;
        return Math.max(20, hitWindowMs + compensation);
    }

    private double getEffectiveThresholdMs(double baseThresholdMs, double calibrationOffsetMs) {
        double compensation = -calibrationOffsetMs;
        return Math.max(0, baseThresholdMs + compensation);
    }

    private void checkLevelEnd(double currentTime) {
        if (levelComplete) {
            return;
        }

        boolean allBeatsGenerated = nextBeatIndex >= chartBeats.size();
        boolean noNotesLeft = notes.isEmpty();

        if (allBeatsGenerated && noNotesLeft && currentTime > 1000) {
            endLevel();
        }
    }

    private void endLevel() {
        levelComplete = true;
    }

    public void draw(Graphics2D g2) {
        if (levelComplete) {
            drawEndScreen(g2);
            return;
        }
        gp.drawGradientBox(g2, 0, 0, gp.screenWidth, gp.screenHeight);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 42));
        g2.drawString(songTitle, 360, 100);

        g2.setColor(Color.CYAN);
        g2.drawOval(targetX - targetRadius, targetY - targetRadius, targetRadius * 2, targetRadius * 2);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.PLAIN, 16));
        g2.drawString("Score: " + Integer.toString(score), gp.screenWidth-200,50);

        double currentTime = System.currentTimeMillis() - startTime;
        g2.setColor(Color.YELLOW);

        for (Note note : notes) {
            if (!note.hit && !note.missed) {
                double timeUntilHit = note.getTargetTimeMs(songOffsetMs, msPerBeat) - currentTime;
                
                // Only draw notes inside the approach window
                if (timeUntilHit <= approachTimeMs && timeUntilHit > -approachTimeMs) {
                    
                    // Calculate progress 't' from 0.0 (start) to 1.0 (target destination)
                    double t = 1.0 - (timeUntilHit / (double)approachTimeMs);
                    
                    int drawX = targetX;
                    int drawY = targetY;

                    // Define your path types here
                    String pathType = "PARABOLA"; // Change this to switch behaviors dynamically

                    if (pathType.equals("PARABOLA")) {
                        // 1. PARABOLIC JUMP PATH
                        int startX = targetX - (pixelsPerSecond * approachTimeMs / 1000);
                        int peakHeight = 200; // How high the note arcs upward

                        // Linear interpolation across X axis
                        drawX = (int)(startX + (targetX - startX) * t);
                        // Parabolic arc for Y axis using vertex form equation: y = -4 * h * (t - 0.5)^2 + k
                        drawY = (int)(targetY - 4 * peakHeight * (t - 0.5) * (t - 0.5) + peakHeight);
                        
                    } else if (pathType.equals("POLAR_SPIRAL")) {
                        // 2. POLAR EQUATION PATH (Spiral spinning inward to the target center)
                        double maxRadius = 300.0; 
                        double currentRadius = maxRadius * (1.0 - t); // Radius shrinks to 0 at target
                        
                        // Theta increases as t advances, causing rotation
                        double turns = 3.0; // Number of complete spins
                        double theta = t * turns * 2.0 * Math.PI; 
                        
                        // Convert polar coordinates (r, theta) to Cartesian system (x, y)
                        drawX = targetX + (int)(currentRadius * Math.cos(theta));
                        drawY = targetY + (int)(currentRadius * Math.sin(theta));
                    }

                    // Render note at calculated math coordinates
                    g2.fillOval(drawX - circleSize / 2, drawY - circleSize / 2, circleSize, circleSize);
                }
            }
        }


        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.PLAIN, 28));
        g2.drawString("Press Enter on the beat!", 450, 580);

        if (!feedback.isEmpty()) {
            g2.setColor(feedback.startsWith("Good") || feedback.startsWith("Perfect") || feedback.startsWith("Great") ? Color.GREEN : Color.RED);
            g2.setFont(new Font("Arial", Font.BOLD, 36));
            g2.drawString(feedback, 480, 480);
        }

        for (VisualParticle p : particles) {
            p.draw(g2);
        }
    }

    private void drawEndScreen(Graphics2D g2) {
        gp.drawGradientBox(g2, 0, 0, gp.screenWidth, gp.screenHeight);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 44));
        g2.drawString("Level Complete", 380, 90);

        g2.setFont(new Font("Arial", Font.PLAIN, 24));
        g2.drawString("Final Score: " + score, 380, 130);

        g2.setFont(new Font("Arial", Font.PLAIN, 20));
        g2.drawString("Enter = Accept, Esc = Deny", 440, gp.screenHeight - 80);
        g2.drawString("Your choice will return you to the level select", 330, gp.screenHeight - 40);
    }

    private void reset() {
        startLevel();
        feedback = "";
        feedbackTimer = 0;
        score = 0;
        resultAccepted = false;
        levelComplete = false;
    }

    public class VisualParticle {
        public double x, y;
        public double velX, velY;
        public int alpha = 255;
        public java.awt.Color color;
        public int size;

        public VisualParticle(double x, double y, double velX, double velY, java.awt.Color color) {
            this.x = x;
            this.y = y;
            this.velX = velX;
            this.velY = velY;
            this.color = color;
            this.size = (int)(Math.random() * 6) + 4;
        }

        public boolean update() {
            x += velX;
            y += velY;
            alpha -= 15;
            return alpha <= 0;
        }

        public void draw(java.awt.Graphics2D g2) {
            g2.setColor(new java.awt.Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            g2.fillOval((int)x - size/2, (int)y - size/2, size, size);
        }
    }

    private void spawnParticles(int direction, java.awt.Color color) {
        for (int i = 0; i < 15; i++) {
            double velY = (Math.random() * 6) - 3;
            double velX = 0;
            if (direction < 0) {
                velX = -(Math.random() * 5 + 2);
            } else if (direction > 0) {
                velX = (Math.random() * 5 + 2);
            } else {
                velX = (Math.random() * 8) - 4;
            }
            particles.add(new VisualParticle(targetX, targetY, velX, velY, color));
        }
    }
}

