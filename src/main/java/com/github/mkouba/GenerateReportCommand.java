package com.github.mkouba;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jboss.logging.Logger;

import com.github.mkouba.GenerateReportCommand.Timeline.Slot;

import io.quarkus.logging.Log;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "generate", mixinStandardHelpOptions = true)
public class GenerateReportCommand implements Runnable {

    private static final Logger LOG = Logger.getLogger(GenerateReportCommand.class);

    static final String BUILD_THREAD_PREFIX = "build_";

    @Parameters(paramLabel = "<file>", description = "Build log file.")
    String file;

    @Option(names = "--top", defaultValue = "10")
    int top;

    @Option(names = "--slot", defaultValue = "50")
    long slot;

    @Option(names = "--out", defaultValue = "report.html")
    String out;

    @Override
    public void run() {
        File buildLog = new File(file);
        if (!buildLog.canRead()) {
            throw new IllegalArgumentException("Build log file cannot be read: " + buildLog);
        }
        LOG.infof("Generate report from the build log file %s", buildLog);
        Map<String, BuildStep> steps = new HashMap<>();
        Map<String, String> currentDups = new HashMap<>();
        AtomicInteger duplicates = new AtomicInteger();
        LocalTime augmentationStarted = null;
        LocalTime augmentationFinished = null;
        String appName = null;

        try {
            List<String> lines = Files.readAllLines(buildLog.toPath());
            for (String line : lines) {
                if (line.contains("Starting step")) {
                    // 14:37:10.636 [build-14] [DEBUG] [io.quarkus.builder] Starting step "io.quarkus.deployment.steps.ClassTransformingBuildStep#handleClassTransformation"
                    String[] parts = line.split("\\s+");
                    LocalTime started = LocalTime.parse(parts[0], DateTimeFormatter.ISO_LOCAL_TIME);
                    String thread = parts[1].substring(1, parts[1].length() - 1);
                    String name = parts[6].substring(1, parts[6].length() - 1).trim();
                    BuildStep step = steps.get(name);
                    if (step != null && (step.isFinished() || !step.thread.equals(thread))) {
                        String newName = name + "_dup" + duplicates.incrementAndGet();
                        LOG.warnf("Step with the same name already exists - using dup suffix: %s", newName);
                        currentDups.put(name, newName);
                        steps.put(newName, new BuildStep(newName, thread, started));
                    } else {
                        steps.put(name, new BuildStep(name, thread, started));
                    }
                } else if (line.contains("Finished step")) {
                    // 14:37:10.873 [build-14] [DEBUG] [io.quarkus.builder] Finished step "io.quarkus.deployment.steps.ClassTransformingBuildStep#handleClassTransformation" in 237 ms 
                    String[] parts = line.split("\\s+");
                    LocalTime finished = LocalTime.parse(parts[0], DateTimeFormatter.ISO_LOCAL_TIME);
                    String thread = parts[1].substring(1, parts[1].length() - 1);
                    String name = parts[6].substring(1, parts[6].length() - 1).trim();
                    BuildStep step = steps.get(name);
                    if (step == null || step.isFinished() || !step.thread.equals(thread)) {
                        step = steps.get(currentDups.get(name));
                        if (step == null) {
                            throw new IllegalStateException("Step not started: " + name);
                        }
                    }
                    if (step.isFinished()) {
                        throw new IllegalStateException("Steps of the same name already finished: " + step);
                    }
                    step.finished = finished;
                } else if (line.contains("Beginning Quarkus augmentation")) {
                    augmentationStarted = LocalTime.parse(line.split("\\s+")[0], DateTimeFormatter.ISO_LOCAL_TIME);
                } else if (line.contains("Quarkus augmentation completed")) {
                    augmentationFinished = LocalTime.parse(line.split("\\s+")[0], DateTimeFormatter.ISO_LOCAL_TIME);
                } else if (line.contains("(f) finalName")) {
                    appName = line.substring(line.lastIndexOf("=") + 1, line.length()).trim();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        Map<String, List<BuildStep>> threadToSteps = new HashMap<>();
        for (BuildStep step : steps.values()) {
            List<BuildStep> threadSteps = threadToSteps.get(step.thread);
            if (threadSteps == null) {
                threadSteps = new ArrayList<>();
                threadToSteps.put(step.thread, threadSteps);
            }
            threadSteps.add(step);
        }
        List<String> threads = threadToSteps.keySet().stream().sorted(new Comparator<String>() {

            @Override
            public int compare(String o1, String o2) {
                int o1n = Integer.parseInt(o1.substring(BUILD_THREAD_PREFIX.length()));
                int o2n = Integer.parseInt(o2.substring(BUILD_THREAD_PREFIX.length()));
                return Integer.compare(o1n, o2n);
            }
        }).collect(Collectors.toList());
        threadToSteps.values().forEach(l -> l.sort(new Comparator<BuildStep>() {
            @Override
            public int compare(BuildStep o1, BuildStep o2) {
                return o1.started.compareTo(o2.started);
            }
        }));

        Duration slotDuration = Duration.ofMillis(slot);

        Map<String, Timeline> threadToTimeline = new HashMap<>();
        for (Entry<String, List<BuildStep>> e : threadToSteps.entrySet()) {
            threadToTimeline.put(e.getKey(),
                    Timeline.from(e.getKey(), e.getValue(), slotDuration, augmentationStarted, augmentationFinished));
        }

        int numberOfSlots = threadToTimeline.values().iterator().next().slots.size();

        // Skip first N empty slots...
        int skipSlots = numberOfSlots;
        for (Timeline timeline : threadToTimeline.values()) {
            OptionalInt firstNonEmpty = IntStream.range(0, timeline.slots.size())
                    .filter(i -> !timeline.slots.get(i).steps.isEmpty())
                    .findFirst();
            if (firstNonEmpty.isPresent() && firstNonEmpty.getAsInt() < skipSlots) {
                skipSlots = firstNonEmpty.getAsInt();
            }
        }

        List<Entry<Integer, Slot>> slotSteps = new ArrayList<>();
        for (int j = 0; j < numberOfSlots; j++) {
            if (j <= skipSlots) {
                continue;
            }
            List<BuildStep> allSteps = new ArrayList<>();
            for (Timeline timeline : threadToTimeline.values()) {
                if (!timeline.slots.get(j).steps.isEmpty()) {
                    allSteps.addAll(timeline.slots.get(j).steps);
                }
            }
            slotSteps.add(Map.entry(j + 1, new Slot(0, 0, allSteps, augmentationStarted, augmentationFinished)));
        }
        slotSteps.sort(new Comparator<Entry<Integer, Slot>>() {

            @Override
            public int compare(Entry<Integer, Slot> o1, Entry<Integer, Slot> o2) {
                return Integer.compare(o1.getValue().steps.size(), o2.getValue().steps.size());
            }
        });

        List<BuildStep> sortedSteps = steps.values().stream().sorted(new Comparator<BuildStep>() {
            @Override
            public int compare(BuildStep o1, BuildStep o2) {
                return o2.getTime().compareTo(o1.getTime());
            }
        }).collect(Collectors.toList());

        File output = new File(out);
        try {
            Files.writeString(output.toPath(),
                    Templates.report(sortedSteps, top, Duration.between(augmentationStarted, augmentationFinished).toMillis(),
                            threadToTimeline, threads, appName, slotSteps,
                            new SlotsInfo(slotDuration, skipSlots, numberOfSlots))
                            .render());
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }

        Log.infof("Executed %s steps on %s threads in %s ms", steps.size(), threadToSteps.size(),
                Duration.between(augmentationStarted, augmentationFinished).toMillis());
    }

    public static class Timeline {

        public List<Slot> slots;

        public Timeline(List<Slot> slots) {
            this.slots = slots;
        }

        public static class Slot {

            public long from;
            public long to;
            public LocalTime start;
            public LocalTime end;
            public List<BuildStep> steps;

            public Slot(long from, long to, List<BuildStep> steps, LocalTime start, LocalTime end) {
                this.steps = steps;
                this.from = from;
                this.to = to;
                this.start = start;
                this.end = end;
            }
        }

        static Timeline from(String thread, List<BuildStep> steps, Duration slotDuration, LocalTime augmentationStarted,
                LocalTime augmentationFinished) {

            List<Slot> slots = new ArrayList<>();
            LocalTime from = augmentationStarted;
            LocalTime to = augmentationStarted.plus(slotDuration);

            while (to.isBefore(augmentationFinished)) {
                List<BuildStep> slotSteps = new ArrayList<>();
                for (BuildStep step : steps) {
                    if (step.started.isBefore(to) && from.isBefore(step.finished)) {
                        slotSteps.add(step);
                    }
                }
                slots.add(new Slot(augmentationStarted.until(from, ChronoUnit.MILLIS),
                        augmentationStarted.until(to, ChronoUnit.MILLIS), slotSteps, from, to));
                from = from.plus(slotDuration);
                to = to.plus(slotDuration);
            }

            LOG.debugf("Timeline created for thread %s [slots=%s]", thread, slots.size());
            return new Timeline(slots);
        }

    }

    public static class BuildStep {

        public String name;
        public String thread;
        public LocalTime started;
        public LocalTime finished;

        public BuildStep(String name, String thread, LocalTime started) {
            this.name = name;
            this.thread = thread;
            this.started = started;
        }

        public Duration getTime() {
            return Duration.between(started, finished);
        }

        public boolean isFinished() {
            return finished != null;
        }

        public String getSimpleName() {
            int lastDot = name.lastIndexOf('.');
            return lastDot == -1 ? name : name.substring(lastDot + 1);
        }

        @Override
        public String toString() {
            return "BuildStep [name=" + name + ", thread=" + thread + ", started=" + started + ", finished=" + finished + "]";
        }

    }

    public static class SlotsInfo {

        public final Duration duration;
        public final int skipped;
        public final long count;

        public SlotsInfo(Duration duration, int skipped, long count) {
            this.duration = duration;
            this.skipped = skipped;
            this.count = count;
        }
    }

}
