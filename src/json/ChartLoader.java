package json;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import utilities.SongChart;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChartLoader {

    private static final String[] CHART_PATH_CANDIDATES = {
        "json/chart.json",
        "src/json/chart.json",
        "src/json/charts.json",
        "/json/chart.json",
        "/src/json/chart.json"
    };

    public static SongChart loadChart(String songKey) {
        SongChart chart = new SongChart();
        chart.beats = new ArrayList<>(); 
        
        Pattern songNamePat = Pattern.compile("\"songName\"\\s*:\\s*\"([^\"]+)\"");
        Pattern bpmPat = Pattern.compile("\"bpm\"\\s*:\\s*(\\d+)"); 
        Pattern offsetPat = Pattern.compile("\"offsetMs\"\\s*:\\s*([\\d.]+)");
        Pattern wavPat = Pattern.compile("\"wav\"\\s*:\\s*\"([^\"]+)\""); 
        Pattern beatNumberPat = Pattern.compile("([\\d.]+)");

        String startBlockIndicator = "\"" + songKey + "\"\\s*:\\s*\\{";

        try (InputStream is = openChartStream()) {
            if (is == null) {
                System.err.println("Could not find a chart file from the available candidates.");
                return null;
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                boolean inSongBlock = false;
                boolean inBeatsArray = false;
                int braceCount = 0;

                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (!inSongBlock) {
                        if (line.matches(".*" + startBlockIndicator + ".*")) {
                            inSongBlock = true;
                            braceCount = 1;
                            line = line.substring(line.indexOf("{") + 1);
                        } else {
                            continue; 
                        }
                    }

                    for (char ch : line.toCharArray()) {
                        if (ch == '{') braceCount++;
                        if (ch == '}') braceCount--;
                    }

                    Matcher m;
                    if ((m = songNamePat.matcher(line)).find()) {
                        chart.songName = m.group(1);
                    }
                    if ((m = bpmPat.matcher(line)).find()) {
                        chart.bpm = Integer.parseInt(m.group(1)); 
                    }
                    if ((m = offsetPat.matcher(line)).find()) {
                        chart.offsetMs = Double.parseDouble(m.group(1));
                    }
                    if ((m = wavPat.matcher(line)).find()) {
                        chart.wav = m.group(1).replace("\\\\", "\\");
                    }

                    if (line.contains("\"beats\"")) {
                        inBeatsArray = true; 
                        String dataSection = line.substring(line.indexOf("\"beats\"") + 7);
                        Matcher sectionMatcher = beatNumberPat.matcher(dataSection);
                        while (sectionMatcher.find()) {
                            try {
                                chart.beats.add(Double.parseDouble(sectionMatcher.group(1)));
                            } catch (NumberFormatException ignored) {}
                        }
                        if (line.contains("]")) {
                            inBeatsArray = false;
                        }
                        
                        if (braceCount <= 0) {
                            break;
                        }
                        continue;
                    }

                    if (inBeatsArray) {
                        Matcher lineMatcher = beatNumberPat.matcher(line);
                        while (lineMatcher.find()) {
                            try {
                                chart.beats.add(Double.parseDouble(lineMatcher.group(1)));
                            } catch (NumberFormatException ignored) {}
                        }
                        if (line.contains("]")) {
                            inBeatsArray = false;
                        }
                    }

                    if (braceCount <= 0) {
                        break;
                    }
                }
            }
            
            return chart;
        } catch (IOException | NumberFormatException | NullPointerException e) {
            System.err.println("Error parsing JSON chart: " + e.getMessage());
            return null;
        }
    }

    private static InputStream openChartStream() throws IOException {
        for (String candidate : CHART_PATH_CANDIDATES) {
            String normalizedPath = candidate.startsWith("/") ? candidate.substring(1) : candidate;

            InputStream is = ChartLoader.class.getResourceAsStream(candidate);
            if (is == null) {
                is = ChartLoader.class.getResourceAsStream("/" + normalizedPath);
            }
            if (is != null) {
                return is;
            }

            Path fallback = Paths.get(candidate);
            if (Files.exists(fallback)) {
                return Files.newInputStream(fallback);
            }

            Path fallback2 = Paths.get("src", normalizedPath);
            if (Files.exists(fallback2)) {
                return Files.newInputStream(fallback2);
            }
        }
        return null;
    }
}
