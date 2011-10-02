package contrail;

import java.io.IOException;

import org.apache.avro.mapred.AvroJob; 
import org.apache.avro.mapred.AvroWrapper;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;
import org.apache.hadoop.mapred.lib.NLineInputFormat;


/**
 * Base class for Hadoop Map/Reduce (MR) jobs for converting fastq file
 * format to a format suitable for hadoop. Subclasses customize the MR job
 * for different file formats.
 * 
 * @author jlewi
 *
 */
public class FastqPreprocessorAvro extends Configured implements Tool 
{	
	private static final Logger sLogger = Logger.getLogger(FastqPreprocessor.class);
	
	
	// FastqPreprocessorMapper
	///////////////////////////////////////////////////////////////////////////
	
	private static class FastqPreprocessorMapper extends MapReduceBase 
    implements Mapper<LongWritable, Text, AvroWrapper<SequenceRead>, NullWritable> 
	{
		private int idx = 0;
		
		private String name = null;
		private String seq  = null;
		private String qv   = null;
		
		private String filename = null;
		private String suffix = null;
		private String counter = "pair_unknown";
		
		public void configure(JobConf job) 
		{
			filename = job.get("map.input.file");
			
			boolean usesuffix = Integer.parseInt(job.get("PREPROCESS_SUFFIX")) == 1;
			
			if (usesuffix)
			{
				if      (filename.contains("_1.")) { suffix = "_1"; counter = "pair_1"; }
				else if (filename.contains("_2.")) { suffix = "_2"; counter = "pair_2"; }
				else                               { counter = "pair_unpaired"; }
				
				System.err.println(filename + " suffix: \"" + suffix + "" + "\"");
			}
		}
		
		public void map(LongWritable lineid, Text line,
                OutputCollector<AvroWrapper<SequenceRead>, NullWritable> output, Reporter reporter)
                throws IOException 
        {
			if (idx == 0) 
			{ 
				name = line.toString();

				if (name.charAt(0) != '@')
				{
					throw new IOException("ERROR: Invalid readname: " + name + " in " + filename);
				}
				
				int i = name.indexOf(' ');
				
				// strip the @ and chop after the first space
				if (i != -1) { name = name.substring(1, i); }
				else         { name = name.substring(1);    }
				
				// clean up any funny characters
				name = name.replaceAll("[:#-.|/]", "_");
				
				if (suffix != null)
				{
					name += suffix;
				}
			}
			else if (idx == 1) { seq = line.toString(); }
			else if (idx == 2) { }
			else if (idx == 3)
			{
				//qv = line.toString();
				//TODO (jlewi): Probably faster to store the sequence read as a member variable and
				// write to it directly
				SequenceRead read = new SequenceRead();
				read.id = name;
				read.dna = seq; 
				output.collect(new AvroWrapper<SequenceRead>(read), NullWritable.get());
				
				reporter.incrCounter("Contrail", "preprocessed_reads", 1);
				reporter.incrCounter("Contrail", counter, 1);
			}
			
			idx = (idx + 1) % 4;
        }
		
		public void close() throws IOException
		{
			if (idx != 0)
			{
				throw new IOException("ERROR: closing with idx = " + idx + " in " + filename);
			}
		}
	}
	
	
	
	// Run Tool
	///////////////////////////////////////////////////////////////////////////	
	
	public RunningJob run(String inputPath, String outputPath) throws Exception
	{ 
		sLogger.info("Tool name: FastqPreprocessor");
		sLogger.info(" - input: "  + inputPath);
		sLogger.info(" - output: " + outputPath);
		
		
		JobConf conf = new JobConf(Stats.class);
		conf.setJobName("FastqPreprocessorAvro " + inputPath);
		
		ContrailConfig.initializeConfiguration(conf);
			
		FileInputFormat.addInputPath(conf, new Path(inputPath));
		FileOutputFormat.setOutputPath(conf, new Path(outputPath));

		conf.setInputFormat(TextInputFormat.class);
		// conf.setOutputFormat(TextOutputFormat.class);

		conf.setMapOutputKeyClass(Text.class);
		conf.setMapOutputValueClass(Text.class);

		conf.setOutputKeyClass(Text.class);
		conf.setOutputValueClass(Text.class);

		conf.setMapperClass(FastqPreprocessorMapper.class);
		conf.setNumReduceTasks(0);
		
        conf.setInputFormat(NLineInputFormat.class);
        conf.setInt("mapred.line.input.format.linespermap", 2000000); // must be a multiple of 4

        // TODO(jlewi): use setoutput codec to set the compression codec. 
        AvroJob.setOutputSchema(conf,new SequenceRead().getSchema());
        
        // Output code doesn't seem to be having any effect
        //AvroJob.setOutputCodec(conf, "zlib");
        
        //use hadoop to compress
        //FileOutputFormat.setCompressOutput(conf,true);
        
		//delete the output directory if it exists already
		FileSystem.get(conf).delete(new Path(outputPath), true);

		return JobClient.runJob(conf);
	}
	

	// Parse Arguments and run
	///////////////////////////////////////////////////////////////////////////	

	public int run(String[] args) throws Exception 
	{
		//String inputPath  = "/Users/mschatz/build/Contrail/data/Ba100k.sim.fq";
		String inputPath  = args[0];
		String outputPath = args[1];
		
		ContrailConfig.PREPROCESS_SUFFIX = 1;
		ContrailConfig.TEST_MODE = true;
		
		run(inputPath, outputPath);
		return 0;
	}


	// Main
	///////////////////////////////////////////////////////////////////////////	

	public static void main(String[] args) throws Exception 
	{
		int res = ToolRunner.run(new Configuration(), new FastqPreprocessorAvro(), args);
		System.exit(res);
	}
}
