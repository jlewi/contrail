/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Author: hJeremy Lewi (jeremy@lewi.us)
package contrail.stages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.codehaus.jackson.JsonFactory;
import org.junit.Test;

import contrail.graph.GraphN50StatsData;
import contrail.graph.LengthStatsData;
import contrail.util.AvroFileUtil;
import contrail.util.FileHelper;

public class TestGraphN50Stats extends GraphN50Stats {
  // Verify the N50Stats data is correct.
  private void assertN50Stats(
      ArrayList<LengthStatsData> lengthsData, GraphN50StatsData n50Stats) {
    // Since the lengths are sorted in descending order the max length
    // should be the first one.
    assertEquals(
        lengthsData.get(0).getLength(),
        new Long(n50Stats.getMaxLength().longValue()));

    // Sum up the lengths and the N50 lengths and make sure they are correct.
    long n50Total = 0L;
    long n50Count = 0L;

    // Check that when we sum the number of contigs up to the minimum
    // length we get the same value as in the n50Stats.
    long numContigs = 0;

    // Sufficient statistics for computing the mean coverage and degree.
    Double coverageStat = 0.0;
    Double degreeStat = 0.0;

    for (int i = 0; i < lengthsData.size(); i++) {
      if (lengthsData.get(i).getLength() < n50Stats.getMinLength()) {
        break;
      }
      LengthStatsData stats = lengthsData.get(i);
      numContigs +=  stats.getCount();

      if (stats.getLength() < n50Stats.getN50Length()) {
        n50Total += stats.getLength() * stats.getCount();
        n50Count += stats.getCount();
      }
      if (stats.getLength() == n50Stats.getN50Length().longValue()) {
        // Figure out how many to
        n50Total += stats.getLength() * (n50Stats.getN50Index() - n50Count);
      }

      coverageStat += stats.getCoverageMean() * stats.getCount();
      degreeStat += stats.getDegreeMean() * stats.getCount();
    }

    assertTrue(n50Total >= n50Stats.getLengthSum() / 2.0);
    assertEquals(numContigs, n50Stats.getNumContigs().longValue());

    Double expectedCoverage = coverageStat / numContigs;
    Double expectedDegree = degreeStat / numContigs;

    assertEquals(expectedCoverage, n50Stats.getMeanCoverage());
    assertEquals(expectedDegree, n50Stats.getMeanDegree());
  }

  protected ArrayList<GraphN50StatsData> readOutput(String outputPath) {
    ArrayList<GraphN50StatsData> results = new ArrayList<GraphN50StatsData>();

    try {
      Schema schema = new GraphN50StatsData().getSchema();
      SpecificDatumReader<GraphN50StatsData> reader =
          new SpecificDatumReader<GraphN50StatsData>(schema);

      FileInputStream inStream = new FileInputStream(outputPath);
      JsonFactory factory = new JsonFactory();
      JsonDecoder decoder = DecoderFactory.get().jsonDecoder(schema, inStream);

      while(true) {
        GraphN50StatsData n50Stats = reader.read(null, decoder);
        if (n50Stats == null) {
          break;
        }
        n50Stats = SpecificData.get().deepCopy(n50Stats.getSchema(), n50Stats);
        results.add(n50Stats);
      }
    } catch(EOFException e) {
      // pass.
    } catch (IOException e) {
      fail(e.getMessage());
    }

    return results;
  }

  @Test
  public void testComputeStats() {
    Random generator = new Random();

    // Create some length stats data.
    ArrayList<Integer> sortedLengths = new ArrayList<Integer>();
    {
      HashSet<Integer> lengths = new HashSet<Integer>();
      int numBins = 30;
      for (int i = 0; i < numBins; ++i) {
       lengths.add(generator.nextInt(500) + 1);
      }
      sortedLengths.addAll(lengths);
      Collections.sort(sortedLengths, Collections.reverseOrder());
    }

    // Generate the lengths data in descending order.
    ArrayList<LengthStatsData> lengthsData = new ArrayList<LengthStatsData>();

    for (Integer length : sortedLengths) {
      LengthStatsData stats = new LengthStatsData();
      stats.setLength(length.longValue());
      stats.setCount(generator.nextInt(1000) + 1L);

      List<Double> coverage =
          Arrays.asList(Math.random() * 10, Math.random() * 10);
      Collections.sort(coverage);

      stats.setCoverageMin(coverage.get(0));
      stats.setCoverageMax(coverage.get(1));
      stats.setCoverageMean(Math.random() * 1000);

      List<Double> degree =
          Arrays.asList(Math.random() * 10, Math.random() * 10);
      Collections.sort(degree);

      stats.setDegreeMin(degree.get(0));
      stats.setDegreeMax(degree.get(1));
      stats.setDegreeMean(Math.random() * 1000);

      lengthsData.add(stats);
    }

    String testDir = FileHelper.createLocalTempDir().getAbsolutePath();
    String lengthsDataPath = FilenameUtils.concat(testDir, "lengths_data.avro");

    AvroFileUtil.writeRecords(
        new Configuration(), new Path(lengthsDataPath), lengthsData);

    GraphN50Stats stage = new GraphN50Stats();
    stage.setParameter("inputpath", lengthsDataPath);

    String outputPath = FilenameUtils.concat(testDir, "n50stats.json");
    stage.setParameter("outputpath", outputPath);

    assertTrue(stage.execute());

    ArrayList<GraphN50StatsData> n50Stats = readOutput(outputPath);

    System.out.println("Output file:" + outputPath);

    for (GraphN50StatsData statsData : n50Stats) {
      assertN50Stats(lengthsData, statsData);
    }
//
//    // Write the data to the file.
//    Schema schema = (new GraphN50StatsData()).getSchema();
//    DatumWriter<GraphN50StatsData> datumWriter =
//        new SpecificDatumWriter<GraphN50StatsData>(schema);
//    DataFileWriter<GraphN50StatsData> writer =
//        new DataFileWriter<GraphN50StatsData>(datumWriter);
//
//    try {
//      FileOutputStream outputStream = new FileOutputStream(
//          File.createTempFile("testGraphN50Stats", "avro"));
//
//      writer.create(schema, outputStream);
//
//      JsonFactory factory = new JsonFactory();
//      JsonGenerator jsonGenerator = factory.createJsonGenerator(outputStream);
//      JsonEncoder encoder = EncoderFactory.get().jsonEncoder(
//          schema, jsonGenerator);
//
//      computeStats(lengthsData, writer, encoder);
//    } catch (IOException exception) {
//      fail(
//          "There was a problem writing the N50 stats to an avro file. " +
//          "Exception: " + exception.getMessage());
//    }
//    computeStats(lengthsData, writer, encoder);
  }
}
