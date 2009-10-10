/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.classifier.bayes.mapreduce.bayes;

import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.mahout.classifier.BayesFileFormatter;
import org.apache.mahout.classifier.ClassifierResult;
import org.apache.mahout.classifier.bayes.algorithm.BayesAlgorithm;
import org.apache.mahout.classifier.bayes.algorithm.CBayesAlgorithm;
import org.apache.mahout.classifier.bayes.common.BayesParameters;
import org.apache.mahout.classifier.bayes.datastore.HBaseBayesDatastore;
import org.apache.mahout.classifier.bayes.datastore.InMemoryBayesDatastore;
import org.apache.mahout.classifier.bayes.exceptions.InvalidDatastoreException;
import org.apache.mahout.classifier.bayes.interfaces.Algorithm;
import org.apache.mahout.classifier.bayes.interfaces.Datastore;
import org.apache.mahout.classifier.bayes.mapreduce.common.BayesConstants;
import org.apache.mahout.classifier.bayes.model.ClassifierContext;
import org.apache.mahout.common.Parameters;
import org.apache.mahout.common.StringTuple;
import org.apache.mahout.common.nlp.NGrams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/** Reads the input train set(preprocessed using the {@link BayesFileFormatter}). */
public class BayesClassifierMapper extends MapReduceBase implements
    Mapper<Text, Text, StringTuple, DoubleWritable> {

  private static final Logger log = LoggerFactory.getLogger(BayesClassifierMapper.class);

  private int gramSize = 1;
  
  ClassifierContext classifier = null;
  
  String defaultCategory = null;
  /**
   * Parallel Classification
   *
   * @param key      The label
   * @param value    the features (all unique) associated w/ this label
   * @param output   The OutputCollector to write the results to
   * @param reporter Reports status back to hadoop
   */
  @Override
  public void map(Text key, Text value,
                  OutputCollector<StringTuple, DoubleWritable> output, Reporter reporter)
      throws IOException {
    //String line = value.toString();
    String label = key.toString();


    //StringBuilder builder = new StringBuilder(label);
    //builder.ensureCapacity(32);// make sure we have a reasonably size buffer to
                               // begin with
    List<String> ngrams  = new NGrams(value.toString(), gramSize).generateNGramsWithoutLabel(); 
    
    try {
      ClassifierResult result = classifier.classifyDocument( ngrams
          .toArray(new String[ngrams.size()]), defaultCategory);
     
      String correctLabel = label;
      String classifiedLabel = result.getLabel();
      
      StringTuple outputTuple = new StringTuple(BayesConstants.CLASSIFIER_TUPLE);
      outputTuple.add(correctLabel);
      outputTuple.add(classifiedLabel);
      
      output.collect(outputTuple, new DoubleWritable(1.0d));
    } catch (InvalidDatastoreException e) {
      throw new IOException(e.toString());
    }
  }

  @Override
  public void configure(JobConf job) {
    try {
      log.info("Bayes Parameter" + job.get("bayes.parameters"));
      Parameters params = BayesParameters.fromString(job.get("bayes.parameters",""));
      log.info("{}", params.print());
      Algorithm algorithm = null;
      Datastore datastore = null;

      
      if (params.get("dataSource").equals("hdfs")) {
        if (params.get("classifierType").equalsIgnoreCase("bayes")) {
          log.info("Testing Bayes Classifier");
          algorithm = new BayesAlgorithm();
          datastore = new InMemoryBayesDatastore(params);
        } else if (params.get("classifierType").equalsIgnoreCase("cbayes")) {
          log.info("Testing Complementary Bayes Classifier");
          algorithm = new CBayesAlgorithm();
          datastore = new InMemoryBayesDatastore(params);
        } else {
          throw new IllegalArgumentException("Unrecognized classifier type: "
              + params.get("classifierType"));
        }

      } else if (params.get("dataSource").equals("hbase")) {
        if (params.get("classifierType").equalsIgnoreCase("bayes")) {
          log.info("Testing Bayes Classifier");
          algorithm = new BayesAlgorithm();
          datastore = new HBaseBayesDatastore(params.get("basePath"), params);
        } else if (params.get("classifierType").equalsIgnoreCase("cbayes")) {
          log.info("Testing Complementary Bayes Classifier");
          algorithm = new CBayesAlgorithm();
          datastore = new HBaseBayesDatastore(params.get("basePath"), params);
        } else {
          throw new IllegalArgumentException("Unrecognized classifier type: "
              + params.get("classifierType"));
        }

      } else {
        throw new IllegalArgumentException("Unrecognized dataSource type: "
            + params.get("dataSource"));
      }
      classifier = new ClassifierContext(algorithm, datastore);
      classifier.initialize();
      
      
      defaultCategory = params.get("defaultCat");
      gramSize = Integer.valueOf(params.get("gramSize"));
    } catch (IOException ex) {
      log.warn(ex.toString(), ex);
    } catch (InvalidDatastoreException e) {
      log.error(e.toString(), e);
    }
  }
}
