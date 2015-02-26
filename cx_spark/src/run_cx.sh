#!/bin/bash

#export SPARK_JAVA_OPTS=-Dlog4j.configuration=log4j.properties
spark-submit --driver-java-options '-Dlog4j.configuration=log4j.properties' --executor-memory 7G --driver-memory 8G --py-files comp_sketch.py,cx.py,rma_utils.py,utils.py,matrix.py,projections.py run_cx.py $@ 2>&1 | tee test.log 