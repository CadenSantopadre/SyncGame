package game;

import java.awt.Color;
import java.awt.Font;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import ui.GamePanel;
import utilities.GameStateManager;
import utilities.KeyHandler;
import json.ChartLoader;
import utilities.SongChart;

public class Calibration {
    private final GamePanel gp;
    private final GameStateManager state;
    private final KeyHandler keyH;

    private final int circleY = 360;
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
    private Clip musicClip;
    private String songPath;

    // --- RPA tracking ---
    private List<Double> rpaHistory = new ArrayList<>();
    private List<Double> offsetHistory = new ArrayList<>();

    // --- End-level state ---
    private boolean levelComplete = false;
    private boolean resultAccepted = false;

    public Calibration(GamePanel gp, GameStateManager state, KeyHandler keyH) {
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
        SongChart chart = ChartLoader.loadChart("Calibration");

        if (chart != null) {
            this.songTitle = chart.songName != null && !chart.songName.isEmpty() ? chart.songName : "Infinite Mode";
            this.bpm = chart.bpm;
            this.songOffsetMs = chart.offsetMs;
            this.songPath = chart.wav;

            if (chart.beats != null) {
                this.chartBeats = chart.beats;
                this.chartBeats.sort(Double::compareTo);
            }
        } else {
            this.songTitle = "Chart missing (Default Mode)";
        }

        this.msPerBeat = (60.0 / bpm) * 1000.0;
        this.nextBeatIndex = 0;

        startTime = System.currentTimeMillis();
        generateBeatsUpTo(lookAheadMs);
        startMusic();
    }

    private void startMusic() {
        if (musicClip != null && musicClip.isRunning()) {
            return;
        }

        if (songPath == null || songPath.isBlank()) {
            return;
        }

        try {
            File audioFile = new File(songPath);
            if (!audioFile.exists()) {
                audioFile = new File("src" + File.separator + "json" + File.separator + new File(songPath).getName());
            }
            if (!audioFile.exists()) {
                return;
            }

            AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile);
            musicClip = AudioSystem.getClip();
            musicClip.open(audioStream);
            FloatControl gainControl = (FloatControl) musicClip.getControl(FloatControl.Type.MASTER_GAIN);
            gainControl.setValue(-10.0f);
            musicClip.start();
            audioStream.close();
        } catch (Exception e) {
            System.err.println("Unable to play song: " + e.getMessage());
        }
    }

    private void generateBeatsUpTo(double targetHorizonMs) {
        while (nextBeatIndex < chartBeats.size()) {
            double customBeatValue = chartBeats.get(nextBeatIndex);
            double targetTimeMs = songOffsetMs + (customBeatValue * msPerBeat);

            if (targetTimeMs <= targetHorizonMs) {
                notes.add(new Note(customBeatValue));
                nextBeatIndex++;
            } else {
                break;
            }
        }
    }

    public void update() {
        if (!levelStarted) {
            startLevel();
        }

        // If we're on the end screen, accept or deny the result based on Enter/Esc
        if (levelComplete) {
            if (keyH.enterPressed) {
                keyH.enterPressed = false;
                resultAccepted = true;
                double averageOffset = offsetHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                gp.setCalibrationOffsetMs(averageOffset);
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

        generateBeatsUpTo(currentTime + approachTimeMs + lookAheadMs);

        if (feedbackTimer > 0) {
            feedbackTimer--;
            if (feedbackTimer == 0) feedback = "";
        }

        for (Note note : notes) {
            double targetTime = note.getTargetTimeMs(songOffsetMs, msPerBeat);
            double diff = currentTime - targetTime;
            if (!note.hit && !note.missed && diff > hitWindowMs) {
                note.missed = true;
                feedback = "Miss!";
                feedbackTimer = 30;
            }
        }

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

            if (closestNote != null && closestDistance <= hitWindowMs) {
                closestNote.hit = true;
                lastOffsetMs = currentTime - closestNote.getTargetTimeMs(songOffsetMs, msPerBeat);
                double absOffset = Math.abs(lastOffsetMs);
                String timingModifier = "";

                if (lastOffsetMs < 0) {
                    timingModifier = " Early";
                } else if (lastOffsetMs > 0) {
                    timingModifier = " Late";
                }

                // --- Record relative phase angle for this hit ---
                recordRelativePhaseAngle(lastOffsetMs);
                offsetHistory.add(lastOffsetMs);

                if (absOffset <= 20) {
                    feedback = "Perfect!";
                    score += 300;
                    spawnParticles(0, Color.GREEN);
                } else if (absOffset <= 45) {
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

        if (keyH.escPressed) {
            state.setState(GameStateManager.LEVEL_SELECT);
            reset();
            keyH.escPressed = false;
        }

        notes.removeIf(note -> (currentTime - note.getTargetTimeMs(songOffsetMs, msPerBeat)) > approachTimeMs);
        particles.removeIf(VisualParticle::update);

        checkLevelEnd(currentTime);
    }

    /**
     * Converts a hit's timing offset (ms) into a phase angle in degrees,
     * relative to the beat period, normalized to -180..180.
     */
    private void recordRelativePhaseAngle(double offsetMs) {
        double rpa = (offsetMs / msPerBeat) * 360.0;
        while (rpa > 180) rpa -= 360;
        while (rpa < -180) rpa += 360;
        rpaHistory.add(rpa);
    }

    private void checkLevelEnd(double currentTime) {
        if (levelComplete) return;

        boolean allBeatsGenerated = nextBeatIndex >= chartBeats.size();
        boolean noNotesLeft = notes.isEmpty();
        boolean musicFinished = (musicClip == null)
                || (!musicClip.isRunning() && musicClip.getMicrosecondPosition() > 0);

        // currentTime > 1000 guard avoids false-triggering before the clip has started
        if (allBeatsGenerated && noNotesLeft && musicFinished && currentTime > 1000) {
            endLevel();
        }
    }

    private void endLevel() {
        levelComplete = true;
        if (musicClip != null) {
            if (musicClip.isRunning()) musicClip.stop();
            musicClip.close();
        }
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
                if (timeUntilHit <= approachTimeMs && timeUntilHit > -approachTimeMs) {
                    double secondsUntilHit = timeUntilHit / 1000.0;
                    int noteX = targetX - (int)(secondsUntilHit * pixelsPerSecond);
                    g2.fillOval(noteX - circleSize / 2, circleY - circleSize / 2, circleSize, circleSize);
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

    /**
     * End-of-level summary screen: final score plus a plot of relative
     * phase angle (timing accuracy) for every hit across the song.
     */
    private void drawEndScreen(Graphics2D g2) {
        gp.drawGradientBox(g2, 0, 0, gp.screenWidth, gp.screenHeight);

        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 44));
        g2.drawString("Level Complete", 380, 90);

        g2.setFont(new Font("Arial", Font.PLAIN, 24));
        g2.drawString("Final Score: " + score, 380, 130);

        drawRpaPlot(g2, 200, 170, gp.screenWidth - 400, 320);

        g2.setFont(new Font("Arial", Font.PLAIN, 20));
        double mean = offsetHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        g2.drawString("Average Timing: " + mean + " ms", gp.screenWidth/2-g2.getFontMetrics().stringWidth("Average Timing: " + mean + " ms"),500);
        g2.drawString("Calibration will be used by later levels", 390, gp.screenHeight-100);
        g2.drawString("Enter = Accept, Esc = Deny", 440, gp.screenHeight-80);
        g2.drawString("Your choice will return you to the level select", 330, gp.screenHeight - 40);
    }

    private void drawRpaPlot(Graphics2D g2, int x, int y, int width, int height) {
        // Axes
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(x, y, width, height);

        g2.setFont(new Font("Arial", Font.PLAIN, 14));
        g2.drawString("Relative Phase Angle per Hit (degrees)", x, y - 10);

        // Reference gridlines at -180, -90, 0, 90, 180
        int[] refValues = {-180, -90, 0, 90, 180};
        g2.setFont(new Font("Arial", Font.PLAIN, 12));
        for (int ref : refValues) {
            int lineY = mapRpaToY(ref, y, height);
            g2.setColor(ref == 0 ? new Color(255, 255, 255, 180) : new Color(255, 255, 255, 70));
            g2.drawLine(x, lineY, x + width, lineY);
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString(ref + "°", x - 40, lineY + 4);
        }

        if (rpaHistory.isEmpty()) {
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString("No hits recorded.", x + width / 2 - 50, y + height / 2);
            return;
        }

        // Plot points, connected, colored by accuracy
        int n = rpaHistory.size();
        int prevPx = -1, prevPy = -1;
        for (int i = 0; i < n; i++) {
            double rpa = rpaHistory.get(i);
            int px = x + (int) ((double) i / Math.max(1, n - 1) * width);
            int py = mapRpaToY(rpa, y, height);

            double absRpa = Math.abs(rpa);
            Color dotColor;
            if (absRpa <= 20) dotColor = Color.GREEN;
            else if (absRpa <= 60) dotColor = Color.ORANGE;
            else dotColor = Color.RED;

            if (prevPx != -1) {
                g2.setColor(new Color(255, 255, 255, 100));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawLine(prevPx, prevPy, px, py);
            }

            g2.setColor(dotColor);
            g2.fillOval(px - 4, py - 4, 8, 8);

            prevPx = px;
            prevPy = py;
        }
    }

    private int mapRpaToY(double rpa, int y, int height) {
        // rpa in [-180, 180] maps to [y, y+height], with 0 in the middle
        double normalized = (rpa + 180.0) / 360.0; // 0..1
        return y + height - (int) (normalized * height);
    }

    private void reset() {
        levelStarted = false;
        levelComplete = false;
        feedback = "";
        feedbackTimer = 0;
        score = 0;
        resultAccepted = false;
        rpaHistory.clear();
        notes.clear();
        particles.clear();
        nextBeatIndex = 0;
        if (musicClip != null) {
            musicClip.close();
            musicClip = null;
        }
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