# Quarkus Build Report

##  How to Use

### Step 1 - Generate Build Metrics JSON File

Run the maven build of your Quarkus application with the following flag: `-Dquarkus.debug.dump-build-metrics=true`, e.g. 

> mvn clean package -DskipTests -Dquarkus.debug.dump-build-metrics=true

A `build-metrics.json` should be created in the `target` directory.

### Step 2 - Build This Application

> mvn clean package

### Step 3 - Generate the Report

> java -jar target/quarkus-app/quarkus-run.jar /path/to/your/build-metrics.json

Use `--out` to specify the output file (default value is `report.html` in the current directory):

> java -jar target/quarkus-app/quarkus-run.jar /path/to/your/build-metrics.json --out /path/to/report.html
