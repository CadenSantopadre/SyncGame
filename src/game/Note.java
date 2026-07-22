package game;

public class Note {
    public double beat;
    public boolean hit = false;
    public boolean missed = false;

    public Note(double beat) {
        this.beat = beat;
    }

    public double getTargetTimeMs(double offsetMs, double msPerBeat) {
        return offsetMs + beat * msPerBeat;
    }
}