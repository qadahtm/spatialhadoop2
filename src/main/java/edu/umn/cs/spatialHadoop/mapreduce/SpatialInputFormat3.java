/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.SplittableCompressionCodec;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.GlobalIndex;
import edu.umn.cs.spatialHadoop.core.Partition;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.ResultCollector;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.mapred.BlockFilter;
import edu.umn.cs.spatialHadoop.mapred.CombineBlockFilter;
import edu.umn.cs.spatialHadoop.nasa.HDFRecordReader;
import edu.umn.cs.spatialHadoop.nasa.HTTPFileSystem;
import edu.umn.cs.spatialHadoop.operations.RangeFilter;
import edu.umn.cs.spatialHadoop.util.FileUtil;

/**
 * @author Ahmed Eldawy
 *
 */
public class SpatialInputFormat3<K extends Rectangle, V extends Shape>
    extends FileInputFormat<K, Iterable<V>> {
  
  private static final Log LOG = LogFactory.getLog(SpatialInputFormat3.class);
  
  /**Query range to apply upon reading the input*/
  public static final String InputQueryRange = "rect";
  
  /**
   * Used to check whether files are compressed or not. Some compressed files
   * (e.g., gz) are not splittable.
   */
  private CompressionCodecFactory compressionCodecs = null;

  @Override
  public RecordReader<K, Iterable<V>> createRecordReader(InputSplit split,
      TaskAttemptContext context) throws IOException, InterruptedException {
      FileSplit fsplit = (FileSplit) split;
      String extension = FileUtil.getExtensionWithoutCompression(fsplit.getPath());
      // If this extension is for a compression, skip it and take the previous
      // extension
      if (extension.equals("hdf")) {
        // HDF File. Create HDFRecordReader
        return (RecordReader)new HDFRecordReader();
      }
      if (extension.equals("rtree")) {
        // File is locally indexed as RTree
        return (RecordReader)new RTreeRecordReader3<V>();
      }
      // For backward compatibility, check if the file is RTree indexed from
      // its signature
      Configuration conf = context != null? context.getConfiguration() : new Configuration();
      if (SpatialSite.isRTree(fsplit.getPath().getFileSystem(conf), fsplit.getPath())) {
        return (RecordReader)new RTreeRecordReader3<V>();
      }
      // Check if a custom record reader is configured with this extension
      Class<?> recordReaderClass = conf.getClass("SpatialInputFormat."
          + extension + ".recordreader", SpatialRecordReader3.class);
      try {
        return (RecordReader<K, Iterable<V>>) recordReaderClass.newInstance();
      } catch (InstantiationException e) {
      } catch (IllegalAccessException e) {
      }
      // Use the default SpatialRecordReader if none of the above worked
      return (RecordReader)new SpatialRecordReader3<V>();
  }
  
  protected void listStatus(final FileSystem fs, Path dir,
      final List<FileStatus> result, BlockFilter filter) throws IOException {
    GlobalIndex<Partition> gindex = SpatialSite.getGlobalIndex(fs, dir);
    if (gindex == null || filter == null) {
      // No global index which means we cannot use the filter function
      FileStatus[] listStatus;
      if (OperationsParams.isWildcard(dir)) {
        // Wild card
        listStatus = fs.globStatus(dir);
      } else {
        listStatus = fs.listStatus(dir, SpatialSite.NonHiddenFileFilter);
      }
      // Add all files under this directory
      for (FileStatus status : listStatus) {
        if (status.isDir()) {
          // Recursively go in subdir
          listStatus(fs, status.getPath(), result, filter);
        } else {
          // A file, just add it
          result.add(status);
        }
      }
    } else {
      final Path indexDir = OperationsParams.isWildcard(dir)?
          dir.getParent() : dir;
      // Use the global index to limit files
      filter.selectCells(gindex, new ResultCollector<Partition>() {
        @Override
        public void collect(Partition partition) {
          try {
            Path cell_path = new Path(indexDir, partition.filename);
            if (!fs.exists(cell_path))
              LOG.warn("Matched file not found: "+cell_path);
            result.add(fs.getFileStatus(cell_path));
          } catch (IOException e) {
            e.printStackTrace();
          }
        }
      });
    }
  }

  @Override
  protected List<FileStatus> listStatus(JobContext job) throws IOException {
    try {
      Configuration jobConf = job.getConfiguration();
      // The block filter associated with this job
      BlockFilter blockFilter = null;
      if (jobConf.get(InputQueryRange) != null) {
        // This job requires a range query
        blockFilter = new RangeFilter(OperationsParams.getShape(jobConf, InputQueryRange));
      }
      // Retrieve the BlockFilter set by the developers in the JobConf
      Class<? extends BlockFilter> blockFilterClass =
          jobConf.getClass(SpatialSite.FilterClass, null, BlockFilter.class);
      if (blockFilterClass != null) {
        BlockFilter userBlockFilter = blockFilterClass.newInstance();
        blockFilter = blockFilter == null ? userBlockFilter :
          new CombineBlockFilter(blockFilter, userBlockFilter);
      }
      if (blockFilter == null) {
        // No block filter specified by user
        LOG.info("No block filter specified");
        return super.listStatus(job);
      }
      // Get all blocks the user wants to process
      blockFilter.configure(jobConf);
      
      // Filter files based on user specified filter function
      List<FileStatus> result = new ArrayList<FileStatus>();
      Path[] inputDirs = getInputPaths(job);
      
      for (Path dir : inputDirs) {
        FileSystem fs = dir.getFileSystem(jobConf);
        listStatus(fs, dir, result, blockFilter);
      }
      
      LOG.info("Spatial filter function matched with "+result.size()+" cells");
      
      return result;
    } catch (InstantiationException e) {
      LOG.warn(e);
      return super.listStatus(job);
    } catch (IllegalAccessException e) {
      LOG.warn(e);
      return super.listStatus(job);
    }
  }
  
  @Override
  protected boolean isSplitable(JobContext context, Path file) {
    try {
      // Create compressionCodecs to be used by isSplitable method
      if (compressionCodecs == null)
        compressionCodecs = new CompressionCodecFactory(context.getConfiguration());
      FileSystem fs = file.getFileSystem(context.getConfiguration());
      // HDF files are not splittable
      if (file.getName().toLowerCase().endsWith(".hdf"))
        return false;
      final CompressionCodec codec = compressionCodecs.getCodec(file);
      if (codec != null && !(codec instanceof SplittableCompressionCodec))
        return false;
      
      // To avoid opening the file and checking the first 8-bytes to look for
      // an R-tree signature, we never split a file read over HTTP
      if (fs instanceof HTTPFileSystem)
        return false;
      // ... and never split a file less than 150MB to perform better with many small files
      if (fs.getFileStatus(file).getLen() < 150 * 1024 * 1024)
        return false;
      return !SpatialSite.isRTree(fs, file);
    } catch (IOException e) {
      LOG.warn("Error while determining whether a file is splittable", e);
      return false; // Safer to not split it
    }
  }
}
