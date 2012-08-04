package contrail.avro;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.specific.SpecificDatumWriter;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

import contrail.graph.GraphNode;
import contrail.graph.GraphNodeData;
import contrail.graph.SimpleGraphBuilder;

public class TestCompressAndCorrect extends CompressAndCorrect {

  /**
   * Create a temporary directory.
   * @return
   */
  private File createTempDir() {
    File temp = null;
    try {
      temp = File.createTempFile("temp", Long.toString(System.nanoTime()));
    } catch (IOException exception) {
      fail("Could not create temporary file. Exception:" +
          exception.getMessage());
    }
    if(!(temp.delete())){
      throw new RuntimeException(
          "Could not delete temp file: " + temp.getAbsolutePath());
    }

    if(!(temp.mkdir())) {
      throw new RuntimeException(
          "Could not create temp directory: " + temp.getAbsolutePath());
    }
    return temp;
  }

  private void writeGraph(File avroFile, Map<String, GraphNode> nodes) {
    // Write the data to the file.
    Schema schema = (new GraphNodeData()).getSchema();
    DatumWriter<GraphNodeData> datumWriter =
        new SpecificDatumWriter<GraphNodeData>(schema);
    DataFileWriter<GraphNodeData> writer =
        new DataFileWriter<GraphNodeData>(datumWriter);

    try {
      writer.create(schema, avroFile);
      for (GraphNode node: nodes.values()) {
        writer.append(node.getData());
      }
      writer.close();
    } catch (IOException exception) {
      fail("There was a problem writing the graph to an avro file. Exception:" +
          exception.getMessage());
    }
  }

  @Test
  public void testRun() {
    // Create a graph and write it to a file.
    SimpleGraphBuilder builder = new SimpleGraphBuilder();
    builder.addKMersForString("ACTGGATT", 3);

    // Add some tips.
    builder.addEdge("ATT", "TTG", 2);
    builder.addEdge("ATT", "TTC", 2);

    File temp = createTempDir();
    File avroFile = new File(temp, "graph.avro");

    writeGraph(avroFile, builder.getAllNodes());

    // Run it.
    CompressAndCorrect stage = new CompressAndCorrect();
    // We need to initialize the configuration otherwise we will get an
    // exception. Normally the initialization happens in main.
    stage.setConf(new Configuration());

    File output_path = new File(temp, "output");

    String[] args =
      {"--inputpath=" + temp.toURI().toString(),
       "--outputpath=" + output_path.toURI().toString(),
       "--K=3", "--localnodes=3"};

    // Catch the following after debugging.
    try {
      stage.run(args);
    } catch (Exception exception) {
      exception.printStackTrace();
      fail("Exception occured:" + exception.getMessage());
    }
  }
}