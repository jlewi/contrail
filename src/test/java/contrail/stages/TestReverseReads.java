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
// Author: Jeremy Lewi (jeremy@lewi.us)
package contrail.stages;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Random;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapred.JobConf;
import org.junit.Test;

import contrail.OutputCollectorMock;
import contrail.ReporterMock;
import contrail.io.FastQText;
import contrail.sequences.AlphabetUtil;
import contrail.sequences.DNAAlphabetFactory;
import contrail.sequences.DNAUtil;
import contrail.sequences.Sequence;

public class TestReverseReads {
  /**
   * Generate a random q value of the specified length.
   * @param generator
   * @param length
   * @return
   */
  private String randomQValue(Random generator, int length) {
    byte[] letters = new byte[length];
    for (int i = 0; i < length; ++i) {
      // qValues are encoded using ascii values between 33 and 126
      letters[i] = (byte)(33 + generator.nextInt(93));
    }

    String qvalue = null;

    try {
      qvalue = new String(letters, "US-ASCII");
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      fail("Could not create qvalue");
    }

    return qvalue;
  }

  private class TestCase {
    public ArrayList<FastQText> input;
    public ArrayList<FastQText> expected;

    public TestCase() {
      input = new ArrayList<FastQText>();
      expected = new ArrayList<FastQText>();
    }
  }

  @Test
  public void test() {
    Random generator = new Random();

    TestCase cases = new TestCase();

    for (int i = 0; i < 10; ++i) {
      FastQText record = new FastQText();
      int length = 37;
      String id = String.format("record_%d", i);
      String read =
          AlphabetUtil.randomString(
              generator, length, DNAAlphabetFactory.create());
      String qvalue = randomQValue(generator, length);

      record.set(id, read, qvalue);

      cases.input.add(record);

      FastQText expected = new FastQText();

      Sequence sequence = new Sequence(read, DNAAlphabetFactory.create());
      String reversedRead = DNAUtil.reverseComplement(sequence).toString();
      String reversedQValue = StringUtils.reverse(qvalue);
      expected.set(id, reversedRead, reversedQValue);

      cases.expected.add(expected);
    }

    OutputCollectorMock<FastQText, NullWritable> collector =
        new OutputCollectorMock<FastQText, NullWritable>(
            FastQText.class, NullWritable.class);
    ReporterMock reporter = new ReporterMock();
    ReverseReads.ReverseMapper mapper = new ReverseReads.ReverseMapper();
    mapper.configure(new JobConf());
    for (FastQText input : cases.input) {
      try {
        mapper.map(
            new LongWritable(0), input, collector, reporter);
      } catch (IOException e) {
        e.printStackTrace();
        fail("Mapper failed.");
      }
    }

    // Check the output.
    assertEquals(cases.expected.size(), collector.outputs.size());

    for (int i = 0; i < cases.expected.size(); ++i) {
      FastQText actual = collector.outputs.get(i).key;
      FastQText expected = cases.expected.get(i);

      assertEquals(expected.getId(), actual.getId());
      assertEquals(expected.getDna(), actual.getDna());
      assertEquals(expected.getQValue(), actual.getQValue());
    }
  }
}
