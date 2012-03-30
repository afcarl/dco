package pls;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.log4j.Logger;

public class SolsOutputFileReader {
	
	private static final Logger LOG = Logger.getLogger(SolsOutputFileReader.class);
	
	public static void main(String[] args) throws IOException, InstantiationException, 
		IllegalAccessException, ClassNotFoundException {
		Path path = new Path(args[0]);
		String solutionClass = args[1];
//		Path path = new Path("/users/sryza/testdir/1331512645039/");
		
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		FileStatus[] statuses = fs.listStatus(path);
		System.out.println("number of subfiles: " + statuses.length);
		Arrays.sort(statuses, new RunFolderComparator());
		for (FileStatus fileStatus : statuses) {
			Path subpath = fileStatus.getPath();
			subpath = new Path(subpath, "part-00000");
			System.out.println("Sols for " + subpath.toString());
			List<PlsSolution> solutions = getFileSolutions(subpath, fs, conf, solutionClass);
			for (PlsSolution sol : solutions) {
				LOG.info("sol cost=" + sol.getCost() + ", id=" + sol.getSolutionId() + ", parent id=" + sol.getParentSolutionId());
			}
			System.out.println();
		}
	}
	
	public static List<PlsSolution> getFileSolutions(Path path, FileSystem fs, Configuration conf, String solutionClass)
		throws IOException, InstantiationException, IllegalAccessException, ClassNotFoundException {
		SequenceFile.Reader reader = new SequenceFile.Reader(fs, path, conf);
		BytesWritable key = new BytesWritable();
		BytesWritable value = new BytesWritable();
		List<PlsSolution> sols = new ArrayList<PlsSolution>();
		while (reader.next(key, value)) {
			if (key.equals(PlsUtil.METADATA_KEY)) {
				//TODO: read best solution so that we can note that it's not among reported if it's not
				continue;
			} else if (!key.equals(PlsUtil.SOLS_KEY)) {
				LOG.warn("Found unexpected key " + new String(key.getBytes()));
			}
			
			PlsSolution sol = (PlsSolution)Class.forName(solutionClass).newInstance();
			sol.buildFromStream(new DataInputStream(new ByteArrayInputStream(value.getBytes())));
			sols.add(sol);
		}
		return sols;
	}
	
	private static class RunFolderComparator implements Comparator<FileStatus> {
		@Override
		public int compare(FileStatus fs1, FileStatus fs2) {
			return getRunNumber(fs1) - getRunNumber(fs2);
		}
		
		private int getRunNumber(FileStatus fs) {
			String name = fs.getPath().getName();
			return Integer.parseInt(name);
		}
	}
}
