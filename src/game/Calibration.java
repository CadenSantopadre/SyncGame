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

    
    private final int circleY = 360;          // Y position notes travel along
    private final int circleSize = 36;        
    private final int targetX = 640;          // X position of the "hit" target
    private final int targetY = 360;          // Y position of the "hit" target - this is only a straight line so still 360
    private final int targetRadius = 36;

    private long startTime;
    private final int pixelsPerSecond = 400;  // How fast notes visually travel across the screen
    private final int approachTimeMs = 1500;  // How long (ms) a note takes to travel to the target
    private final int hitWindowMs = 80;       // +/- window (ms) around a beat that still counts as a "hit"


    public int score = 0;
    public double lastOffsetMs = 0;           // Timing offset (ms) of the most recent hit
    private List<Note> notes;
    private String feedback = "";             // Text shown after a hit/miss (e.g. "Perfect!")
    private int feedbackTimer = 0;
    private String songTitle = "Loading...";  // Display name of the song, pulled from the chart
    private List<VisualParticle> particles;
    private boolean levelStarted = false;

    private int bpm = 120;                    // Beats per minute of the song as a fallback - in case it doesn't load
    private double songOffsetMs = 1000.0;     // also fallback
    private double msPerBeat;                 //also fallback

    private List<Double> chartBeats = new ArrayList<>(); // Raw beat positions loaded from the chart JSON
    private int nextBeatIndex = 0;                        // Index of the next beat we haven't spawned a Note for yet
    private final int lookAheadMs = 2000;                 // How far into the future we pre-generate notes
    private Clip musicClip;                               // The actual audio clip being played
    private String songPath;                              // File path to the song's audio file

    // These lists store data across every hit in the level so we can graph
    // and average the player's timing accuracy at the end.
    private List<Double> rpaHistory = new ArrayList<>();      // Each hit's timing offset converted to an angle (-180..180)
    private List<Double> offsetHistory = new ArrayList<>();   // Each hit's raw timing offset in milliseconds

    private boolean levelComplete = false;    // True once all notes/music have finished and results screen is showing
    private boolean resultAccepted = false;   // Whether the player chose to accept the calibration result

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
            // Fall back to "Infinite Mode" if the chart didn't specify a song name
            this.songTitle = chart.songName != null && !chart.songName.isEmpty() ? chart.songName : "Infinite Mode";
            this.bpm = chart.bpm;
            this.songOffsetMs = chart.offsetMs;
            this.songPath = chart.wav;

            if (chart.beats != null) {
                this.chartBeats = chart.beats;
                this.chartBeats.sort(Double::compareTo); // Make sure beats are in chronological order
            }
        } else {
            // Chart file missing/failed to load — still let the level run in a degraded state
            this.songTitle = "Chart missing (Default Mode)";
        }

        // Convert BPM into milliseconds-per-beat, since our timing math works in ms
        this.msPerBeat = (60.0 / bpm) * 1000.0;
        this.nextBeatIndex = 0;

        startTime = System.currentTimeMillis();
        generateBeatsUpTo(lookAheadMs); // Pre-spawn the first wave of notes
        startMusic();
    }

    private void startMusic() {
        if (musicClip != null && musicClip.isRunning()) {
            return; // Already playing, don't restart
        }

        if (songPath == null || songPath.isBlank()) {
            return; // No song configured
        }

        try {
            File audioFile = new File(songPath);
            if (!audioFile.exists()) {
                // Fallback: try looking for the file by name in the default json/ resource folder
                audioFile = new File("src" + File.separator + "json" + File.separator + new File(songPath).getName());
            }
            if (!audioFile.exists()) {
                return; // Give up gracefully if we still can't find it
            }

            AudioInputStream audioStream = AudioSystem.getAudioInputStream(audioFile);
            musicClip = AudioSystem.getClip();
            musicClip.open(audioStream);

            // Lower the volume a bit so it doesn't blast the player
            FloatControl gainControl = (FloatControl) musicClip.getControl(FloatControl.Type.MASTER_GAIN);
            gainControl.setValue(-10.0f);

            musicClip.start();
            audioStream.close();
        } catch (Exception e) {
            // Non-fatal: just log it and move on without music
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
                break; // Beats are sorted, so once one is too far out, all the rest are too
            }
        }
    }

    public void update() {
        if (!levelStarted) {
            startLevel();
        }

        // Once the level is complete, update() just waits for the player to
        // accept or deny the calibration result instead of running gameplay logic.
        if (levelComplete) {
            if (keyH.enterPressed) {
                keyH.enterPressed = false;
                resultAccepted = true;
                // Average every recorded hit offset into a single calibration value
                double averageOffset = offsetHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
                gp.setCalibrationOffsetMs(averageOffset);
                state.setState(GameStateManager.LEVEL_SELECT);
                reset();
            } else if (keyH.escPressed) {
                keyH.escPressed = false;
                resultAccepted = false; // Player rejected the calibration, discard it
                state.setState(GameStateManager.LEVEL_SELECT);
                reset();
            }
            return; // Skip all gameplay logic below while on the results screen
        }

        // How far (ms) we are into the level right now
        double currentTime = System.currentTimeMillis() - startTime;

        // Keep spawning notes as time progresses (rolling look-ahead window)
        generateBeatsUpTo(currentTime + approachTimeMs + lookAheadMs);

        // Countdown the on-screen feedback text ("Perfect!", "Miss!", etc.)
        if (feedbackTimer > 0) {
            feedbackTimer--;
            if (feedbackTimer == 0) feedback = "";
        }

        // Any note whose hit window has fully passed without being hit gets flagged as missed
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

            // Find whichever unresolved note is closest in time to "now" —
            // this is the note the player is most likely trying to hit
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
                // Successful hit within the timing window
                closestNote.hit = true;
                lastOffsetMs = currentTime - closestNote.getTargetTimeMs(songOffsetMs, msPerBeat);
                double absOffset = Math.abs(lastOffsetMs);
                String timingModifier = "";

                // Label whether the press was early or late (purely cosmetic feedback)
                if (lastOffsetMs < 0) {
                    timingModifier = " Early";
                } else if (lastOffsetMs > 0) {
                    timingModifier = " Late";
                }

                // --- Record this hit's timing data for the end-of-level graph/average ---
                recordRelativePhaseAngle(lastOffsetMs);
                offsetHistory.add(lastOffsetMs);

                // Tiered scoring: tighter timing = more points + nicer feedback
                if (absOffset <= 20) {
                    feedback = "Perfect!";
                    score += 300;
                    spawnParticles(0, Color.GREEN); // 0 = burst outward in all directions
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
                // Either nothing to hit, or the closest note was outside the hit window
                feedback = "Miss!";
                feedbackTimer = 30;
            }
        }

        // Let the player bail out of calibration early via Escape
        if (keyH.escPressed) {
            state.setState(GameStateManager.LEVEL_SELECT);
            reset();
            keyH.escPressed = false;
        }

        // Clean up notes that are long past their hit window (avoids the list growing forever)
        notes.removeIf(note -> (currentTime - note.getTargetTimeMs(songOffsetMs, msPerBeat)) > approachTimeMs);

        // Update all active particles and remove any that have fully faded out.
        // VisualParticle.update() returns true when it's "dead" (alpha <= 0).
        particles.removeIf(VisualParticle::update);

        checkLevelEnd(currentTime);
    }


    private void recordRelativePhaseAngle(double offsetMs) {
        double rpa = (offsetMs / msPerBeat) * 360.0;
        // Wrap into the -180..180 range (handles offsets larger than half a beat)
        while (rpa > 180) rpa -= 360;
        while (rpa < -180) rpa += 360;
        rpaHistory.add(rpa);
    }

    private void checkLevelEnd(double currentTime) {
        if (levelComplete) return; // Already ended, nothing to do

        boolean allBeatsGenerated = nextBeatIndex >= chartBeats.size();
        boolean noNotesLeft = notes.isEmpty();
        boolean musicFinished = (musicClip == null)
                || (!musicClip.isRunning() && musicClip.getMicrosecondPosition() > 0);

        // The currentTime > 1000 guard avoids a false "level complete" trigger
        // in the brief moment right at the start before the clip has actually begun playing
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

        // Background
        gp.drawGradientBox(g2, 0, 0, gp.screenWidth, gp.screenHeight);

        // Song title header
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.BOLD, 42));
        g2.drawString(songTitle, 360, 100);

        // The stationary target ring notes should land on
        g2.setColor(Color.CYAN);
        g2.drawOval(targetX - targetRadius, targetY - targetRadius, targetRadius * 2, targetRadius * 2);

        // Score display in the top-right corner
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.PLAIN, 16));
        g2.drawString("Score: " + Integer.toString(score), gp.screenWidth-200,50);

        // --- Draw each note sliding toward the target ---
        double currentTime = System.currentTimeMillis() - startTime;
        g2.setColor(Color.YELLOW);
        for (Note note : notes) {
            if (!note.hit && !note.missed) {
                double timeUntilHit = note.getTargetTimeMs(songOffsetMs, msPerBeat) - currentTime;
                // Only draw notes that are within the visible approach window (before or just after the target)
                if (timeUntilHit <= approachTimeMs && timeUntilHit > -approachTimeMs) {
                    double secondsUntilHit = timeUntilHit / 1000.0;
                    // Notes move right-to-left toward targetX at a constant pixel speed
                    int noteX = targetX - (int)(secondsUntilHit * pixelsPerSecond);
                    g2.fillOval(noteX - circleSize / 2, circleY - circleSize / 2, circleSize, circleSize);
                }
            }
        }

        // Instructional prompt
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Arial", Font.PLAIN, 28));
        g2.drawString("Press Enter on the beat!", 450, 580);

        // Transient hit/miss feedback text (green for good hits, red for misses)
        if (!feedback.isEmpty()) {
            g2.setColor(feedback.startsWith("Good") || feedback.startsWith("Perfect") || feedback.startsWith("Great") ? Color.GREEN : Color.RED);
            g2.setFont(new Font("Arial", Font.BOLD, 36));
            g2.drawString(feedback, 480, 480);
        }

        // Particle burst effects layered on top
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

        // The timing-accuracy graph (see drawRpaPlot below)
        drawRpaPlot(g2, 200, 170, gp.screenWidth - 400, 320);

        g2.setFont(new Font("Arial", Font.PLAIN, 20));
        double mean = offsetHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        // Center the "Average Timing" text horizontally using its rendered width
        g2.drawString("Average Timing: " + mean + " ms", gp.screenWidth/2-g2.getFontMetrics().stringWidth("Average Timing: " + mean + " ms"),500);

        g2.drawString("Calibration will be used by later levels", 390, gp.screenHeight-100);
        g2.drawString("Enter = Accept, Esc = Deny", 440, gp.screenHeight-80);
        g2.drawString("Your choice will return you to the level select", 330, gp.screenHeight - 40);
    }

    private void drawRpaPlot(Graphics2D g2, int x, int y, int width, int height) {
        // Draw the graph's bounding box
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRect(x, y, width, height);

        g2.setFont(new Font("Arial", Font.PLAIN, 14));
        g2.drawString("Relative Phase Angle per Hit (degrees)", x, y - 10);

        // Reference gridlines at -180, -90, 0 (perfect), 90, 180 degrees
        int[] refValues = {-180, -90, 0, 90, 180};
        g2.setFont(new Font("Arial", Font.PLAIN, 12));
        for (int ref : refValues) {
            int lineY = mapRpaToY(ref, y, height);
            // Make the 0-degree ("perfect") line stand out more than the others
            g2.setColor(ref == 0 ? new Color(255, 255, 255, 180) : new Color(255, 255, 255, 70));
            g2.drawLine(x, lineY, x + width, lineY);
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString(ref + "°", x - 40, lineY + 4);
        }

        // Edge case: no hits recorded at all (e.g. player missed everything)
        if (rpaHistory.isEmpty()) {
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString("No hits recorded.", x + width / 2 - 50, y + height / 2);
            return;
        }

        // Plot each hit as a dot, connected by lines, colored by how accurate it was
        int n = rpaHistory.size();
        int prevPx = -1, prevPy = -1;
        for (int i = 0; i < n; i++) {
            double rpa = rpaHistory.get(i);

            // Spread points evenly left-to-right across the graph width
            int px = x + (int) ((double) i / Math.max(1, n - 1) * width);
            int py = mapRpaToY(rpa, y, height);

            // Color-code accuracy: green = tight, orange = okay, red = way off
            double absRpa = Math.abs(rpa);
            Color dotColor;
            if (absRpa <= 20) dotColor = Color.GREEN;
            else if (absRpa <= 60) dotColor = Color.ORANGE;
            else dotColor = Color.RED;

            // Connect to the previous point with a faint line so the trend is easy to read
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
        double normalized = (rpa + 180.0) / 360.0; // Rescale -180..180 into 0..1
        return y + height - (int) (normalized * height); // Flip so positive angles go downward
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
        public double x, y;                 // Current position
        public double velX, velY;           // Velocity per update tick
        public int alpha = 255;             // Current transparency (255 = fully opaque)
        public java.awt.Color color;        // Particle color (matches hit quality)
        public int size;                    // Random-ish diameter for visual variety

        public VisualParticle(double x, double y, double velX, double velY, java.awt.Color color) {
            this.x = x;
            this.y = y;
            this.velX = velX;
            this.velY = velY;
            this.color = color;
            this.size = (int)(Math.random() * 6) + 4; // Random size between 4 and 9 px
        }

        /*
          Moves the particle and fades it out a bit.
          @return true once the particle has fully faded
         */
        public boolean update() {
            x += velX;
            y += velY;
            alpha -= 15;
            return alpha <= 0;
        }

        // Draws the particle using its current position, size, color, and fade level
        public void draw(java.awt.Graphics2D g2) {
            g2.setColor(new java.awt.Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
            g2.fillOval((int)x - size/2, (int)y - size/2, size, size);
        }
    }

    private void spawnParticles(int direction, java.awt.Color color) {
        for (int i = 0; i < 15; i++) {
            double velY = (Math.random() * 6) - 3; // Slight random vertical spread
            double velX = 0;
            if (direction < 0) {
                velX = -(Math.random() * 5 + 2); // Fly left
            } else if (direction > 0) {
                velX = (Math.random() * 5 + 2);  // Fly right
            } else {
                velX = (Math.random() * 8) - 4;  // Fly outward in a random direction
            }
            particles.add(new VisualParticle(targetX, targetY, velX, velY, color));
        }
    }
}