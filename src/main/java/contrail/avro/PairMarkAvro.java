package contrail.avro;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.apache.avro.mapred.AvroCollector;
import org.apache.avro.mapred.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;

import contrail.graph.EdgeDirection;
import contrail.graph.EdgeTerminal;
import contrail.graph.GraphNode;
import contrail.graph.TailData;
import contrail.sequences.DNAStrand;
import contrail.sequences.StrandsUtil;


/**
 * For each node we randomly flip a coin and assign the node a value of Up
 * or Down. The random seed is based on a global seed value and the node id.
 * Thus, each node can compute the result of the coin toss for any other node.
 * 
 * Consider Two nodes A->B. Suppose A has a single outgoing edge to B and
 * B has a single incoming edge from A. In this case, nodes A and B can be
 * merged. However, these two nodes will be merged during the merge phase 
 * (PairMerge) only if we mark them to be merge. 
 * 
 * We mark A and B to be merged in the following cases.
 * 
 * Suppose the coin toss for A is Up and Down for B.  
 * 
 */
public class PairMarkAvro extends Stage {	
	private static final Logger sLogger = Logger.getLogger(PairMarkAvro.class);
	
	private static class CompressibleNode {
	  private CompressibleNodeData data;
	  private GraphNode node;
	  
	  public void setData(CompressibleNodeData new_data) {
	    data = new_data;
	    node.setData(new_data.getNode());
	  }
	  
	  public GraphNode getNode() {
	    return node;
	  }
	  
	  /**
	   * Whether the given strand of the node can be compressed.
	   * @param strand
	   * @return
	   */
	  public boolean canCompress(DNAStrand strand){
	    if (data.getCompressibleStrands() == CompressibleStrands.BOTH) {
	      return true;
	    }
	    if (strand == DNAStrand.FORWARD && 
	        data.getCompressibleStrands() == CompressibleStrands.FORWARD) {
	        return true;
	    }
	    if (strand == DNAStrand.REVERSE && 
          data.getCompressibleStrands() == CompressibleStrands.REVERSE) {
          return true;
      }
	    return false;
	  }
	}
	
	private static class PairMarkMapper extends 
	  AvroMapper<CompressibleNodeData, Pair<CharSequence, MergeNodeData>> {
		private static long randseed = 0;
		private Random rfactory = new Random();
		
		public void configure(JobConf job) {
			randseed = Long.parseLong(job.get("randseed"));
		}
		
		private enum CoinFlip {
		  Up, Down;
		}
		
		// Flip a coin for the given node. The seed for the random 
		// generator is a combination of a global seed and the nodeid.
		// Thus, all nodes compute the same value for the flip for a given node.
		//
		// TODO(jlewi): We shouldn't use Heads and Tails because we already
		// use tails to refer to tails in the chain. We don't want to use
		// Male/Female because the abbreviation "F" gets confused with the
		// forward strand.
		// We could use Up and Down. 
		
		// TODO(jlewi): We should consider making the coin flipper a separat
		// class so we can use dependency inject it so during testing
		// we can control the flip values for the different nodes.
		public CoinFlip flip(String nodeid) {
			rfactory.setSeed(nodeid.hashCode() ^ randseed);
			
			double rand = rfactory.nextDouble();			
			CoinFlip side = (rand >= .5) ? CoinFlip.Up : CoinFlip.Down;			
			return side;
		}
		
		
		public TailData getBuddy(CompressibleNode node, DNAStrand strand) {
			if (node.canCompress(strand)) {
				return node.getNode().getTail(strand, EdgeDirection.OUTGOING);
			}			
			return null;
		}
		
		private CompressibleNode node;
		private MergeNodeData output;
		
		// Output pair
		private Pair<CharSequence, MergeNodeData> out_pair;
		
		private void clearOutput () {
		  output.setNode(null);
		  output.setStrandsToMerge(null);
		}
		
		// Container class for storing information about which edge in this node
		// to compress.
		private static class EdgeToCompress {
		  public EdgeToCompress(EdgeTerminal terminal, DNAStrand dna_strand) {
		    other_terminal = terminal;
		    strand = dna_strand;
		  }
		  // This is the terminal for the edge we are going to compress.
		  public EdgeTerminal other_terminal;
		  
		  // This is the strand of this node which is connected to 
		  // other_terminal and which gets compressed.
		  public DNAStrand strand;
		}
		
		// Handle a node which is a assigned Up by the coin toss.
		// Since the node is assigned Up we can merge it into one of its
		// tails if that tails is assigned Down.
		private EdgeToCompress processUpNode(
		    CompressibleNode node, TailData fbuddy, TailData rbuddy) {		  
      // Prefer Merging forward if we can.
      // We can only merge in a single direction at a time.
      if (fbuddy != null) {
        
        CoinFlip f_flip = flip(fbuddy.terminal.nodeId);

        if (f_flip == CoinFlip.Down) {
          // We can compress the forward strand.              
          return new EdgeToCompress(fbuddy.terminal, DNAStrand.FORWARD);
        }
      }

      // If we can't compress the forward strand, see if
      // we can compress the reverse strand.
      if (rbuddy != null) {      
        CoinFlip r_flip = flip(rbuddy.terminal.nodeId);

        if (r_flip == CoinFlip.Down) { 
          return new EdgeToCompress(rbuddy.terminal, DNAStrand.REVERSE);
        }
      }
      
      // Can't do a merge. 
      // Should we ever be able to reach this point?
      return null;
		}
		
		// If this node was randomly assigned a state of Down then we can 
		// potentially convert it to Up and merge it with some of its neighbors.
		// If a node is assigned Down normally we won't do anything because
    // if its connected to a Up node the Up Node will be sent by the mapper
    // to a Down node its connected to and the Reducer will merge the two
    // nodes. However, suppose we have a sequence of two or more Down nodes
    // in a row. Then normally none of the nodes would get merged. 
    // However, we can potentially convert the down node to an Up Node
    // and do a merge.    
		private boolean convertDownToUp(
		    CompressibleNode node, TailData fbuddy, TailData rbuddy) {
		  
		  // This node is a tail.
      if ((rbuddy != null) && (fbuddy != null)) {
        // We have tails for both strands of this node.
        
        //boolean fmale = isMale(fbuddy.id);
        //boolean rmale = isMale(rbuddy.id);
        CoinFlip f_flip = flip(fbuddy.terminal.nodeId);
        CoinFlip r_flip = flip(rbuddy.terminal.nodeId);

        if ( f_flip == CoinFlip.Down &&  r_flip == CoinFlip.Down &&
            (node.getNode().getNodeId().compareTo(
                fbuddy.terminal.nodeId) < 0) && 
            (node.getNode().getNodeId().compareTo(
                rbuddy.terminal.nodeId) < 0)) {
          
          return true;
        }
        return false;
      }
      
      if (rbuddy == null) {
        // We only have a tail for the forward strand but not the reverse
        // strand.
        //boolean fmale = isMale(fbuddy.id);
        CoinFlip f_flip = flip(fbuddy.terminal.nodeId);
        if (f_flip == CoinFlip.Down && (
            node.getNode().getNodeId().compareTo(
                fbuddy.terminal.nodeId) < 0)) {

          return true;
        }        
        return false;
      }
      
      if (fbuddy == null) {
        //boolean rmale = isMale(rbuddy.id);

        CoinFlip r_flip = flip(rbuddy.terminal.nodeId);
        
        if (r_flip == CoinFlip.Down && (
            node.getNode().getNodeId().compareTo(
                rbuddy.terminal.nodeId) < 0)) {
          // Its FF=>X* and I'm the local minimum
          return true;
        }
        return false;
      }
      return false;
		}
		
		public void map(CompressibleNodeData node_data, 
		    AvroCollector<Pair<CharSequence, MergeNodeData>> collector, 
        Reporter reporter) throws IOException {					
		  node.setData(node_data);
		  
		  // Check if either the forward or reverse strand can be merged.
			TailData fbuddy = getBuddy(node, DNAStrand.FORWARD);
			TailData rbuddy = getBuddy(node, DNAStrand.REVERSE);
												      
      if (fbuddy == null && rbuddy == null) {
        // Node can't be compressed so output the node and we are done.      
        clearOutput();
        output.setNode(node.getNode().getData());
        out_pair.key(node_data.getNode().getNodeId());
        out_pair.value(output);
        collector.collect(out_pair);
        reporter.incrCounter("Contrail", "nodes", 1);
        return;
      }
           
			reporter.incrCounter("Contrail", "compressible", 1);

			CoinFlip coin = flip(node.getNode().getNodeId());
				
			// If this node is randomly assigned Down, see if it can be converted 
			// to up.
			if (coin == CoinFlip.Down) {
			  if (convertDownToUp(node, fbuddy, rbuddy)) {
			    coin = CoinFlip.Up;
			  }
			}
				
			if (coin == CoinFlip.Down) {
			  // Just output this node since this is a Down node
			  // any node to be merged with this node will be sent to this node.
			  clearOutput();
        output.setNode(node.getNode().getData());
        out_pair.key(node_data.getNode().getNodeId());
        out_pair.value(output);
        collector.collect(out_pair);
        reporter.incrCounter("Contrail", "nodes", 1);
			  return;
			}
				
		  // Check if this node can be sent to one of its neighbors to be merged.
		  EdgeToCompress edge_to_compress = 
		      processUpNode(node, fbuddy, rbuddy);
				  

		  if (edge_to_compress == null) {
		    // This node doesn't get sent to another node be merged.
		    clearOutput();
        output.setNode(node.getNode().getData());
        out_pair.key(node_data.getNode().getNodeId());
        out_pair.value(output);
        collector.collect(out_pair);
        reporter.incrCounter("Contrail", "nodes", 1);
        return;
		  }

 
			  // The key for the output is the id for the node this node gets merged
			  // into
		    clearOutput();
		    
		    output.setNode(node.getNode().getData());
		    output.setStrandToMerge(edge_to_compress.strand);
			  out_pair.key(edge_to_compress.other_terminal.nodeId);                
        out_pair.value(output);
            }
	}
	
	private static class PairMarkReducer extends MapReduceBase 
	implements Reducer<Text, Text, Text, Text> 
	{
		private static long randseed = 0;
		
		public void configure(JobConf job) {
			randseed = Long.parseLong(job.get("randseed"));
		}
		
		private class Update
		{
			public String oid;
			public String odir;
			public String nid;
			public String ndir;
		}
		
		public void reduce(Text nodeid, Iterator<Text> iter,
				OutputCollector<Text, Text> output, Reporter reporter)
				throws IOException 
		{
			Node node = new Node(nodeid.toString());
			List<Update> updates = new ArrayList<Update>();
			
			int sawnode = 0;
			
			while(iter.hasNext())
			{
				String msg = iter.next().toString();
				
				//System.err.println(key.toString() + "\t" + msg);
				
				String [] vals = msg.split("\t");
				
				if (vals[0].equals(Node.NODEMSG))
				{
					node.parseNodeMsg(vals, 0);
					sawnode++;
				}
				else if (vals[0].equals(Node.UPDATEMSG))
				{
					Update up = new Update();
					
					up.oid  = vals[1];
					up.odir = vals[2];
					up.nid  = vals[3];
					up.ndir = vals[4];
					
					updates.add(up);
				}
				else
				{
					throw new IOException("Unknown msgtype: " + msg);
				}
			}
			
			if (sawnode != 1)
			{
				throw new IOException("ERROR: Didn't see exactly 1 nodemsg (" + sawnode + ") for " + nodeid.toString());
			}
			
			if (updates.size() > 0)
			{
				for(Update up : updates)
				{
					node.replacelink(up.oid, up.odir, up.nid, up.ndir);
				}
			}
			
			output.collect(nodeid, new Text(node.toNodeMsg()));
		}
	}

	
	public RunningJob run(String inputPath, String outputPath, long randseed) throws Exception
	{ 
		sLogger.info("Tool name: PairMark");
		sLogger.info(" - input: "  + inputPath);
		sLogger.info(" - output: " + outputPath);
		sLogger.info(" - randseed: " + randseed);
		
		JobConf conf = new JobConf(Stats.class);
		conf.setJobName("PairMark " + inputPath);
		
		ContrailConfig.initializeConfiguration(conf);
		conf.setLong("randseed", randseed);
			
		FileInputFormat.addInputPath(conf, new Path(inputPath));
		FileOutputFormat.setOutputPath(conf, new Path(outputPath));

		conf.setInputFormat(TextInputFormat.class);
		conf.setOutputFormat(TextOutputFormat.class);

		conf.setMapOutputKeyClass(Text.class);
		conf.setMapOutputValueClass(Text.class);

		conf.setOutputKeyClass(Text.class);
		conf.setOutputValueClass(Text.class);

		conf.setMapperClass(PairMarkMapper.class);
		conf.setReducerClass(PairMarkReducer.class);

		//delete the output directory if it exists already
		FileSystem.get(conf).delete(new Path(outputPath), true);

		return JobClient.runJob(conf);
	}
	
	
	public int run(String[] args) throws Exception 
	{
		String inputPath  = "/Users/mschatz/try/compressible/";
		String outputPath = "/users/mschatz/try/mark1/";
		long randseed = 123456789;
		
		run(inputPath, outputPath, randseed);
		
		return 0;
	}

	public static void main(String[] args) throws Exception 
	{
		int res = ToolRunner.run(new Configuration(), new PairMark(), args);
		System.exit(res);
	}


  @Override
  protected int run() throws Exception {
    // TODO Auto-generated method stub
    return 0;
  }
}
