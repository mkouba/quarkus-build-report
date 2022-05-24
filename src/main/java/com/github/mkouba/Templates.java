package com.github.mkouba;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.github.mkouba.GenerateReportCommand.BuildStep;
import com.github.mkouba.GenerateReportCommand.SlotsInfo;
import com.github.mkouba.GenerateReportCommand.Timeline;
import com.github.mkouba.GenerateReportCommand.Timeline.Slot;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

@CheckedTemplate
public class Templates {

    native static TemplateInstance report(List<BuildStep> steps, int top, long totalTime, Map<String, Timeline> threadToTimeline,
            List<String> threads, String appName, List<Entry<Integer, Slot>> slotSteps, SlotsInfo slots);

}
