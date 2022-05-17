# Quarkus Build Report

##  How to Use

### Step 1 - Build Log File

Run the maven build of your Quarkus application with the following flags: `-X`, `-Dorg.slf4j.simpleLogger.showThreadName=true`, `-Dorg.slf4j.simpleLogger.showDateTime=true` and `-Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS"`, e.g. 

> mvn -X clean package -DskipTests -Dorg.slf4j.simpleLogger.showThreadName=true -Dorg.slf4j.simpleLogger.showDateTime=true  -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS" > build_log.txt

### Step 2 - Build This Application

> mvn clean package

### Step 3 - Generate the Report

> java -jar target/quarkus-app/quarkus-run.jar /path/to/your/build_log.txt

Use `--top` to specify the number of top time-consuming steps (default value is `10`):

> java -jar target/quarkus-app/quarkus-run.jar /path/to/your/build_log.txt --top 20

Use `--slot` to specify the "size" of the timeline slot (default value is `50ms`):

> java -jar target/quarkus-app/quarkus-run.jar /path/to/your/build_log.txt --slot 100

Use `--out` to specify the output file (default value is `report.html` in the current directory):

> java -jar target/quarkus-app/quarkus-run.jar /path/to/your/build_log.txt --out /path/to/report.html
