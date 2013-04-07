/**
 * Copyright 2012 Google Inc. All Rights Reserved.
 *
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

package contrail.util;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

/**
 * Some routines for working with files.
 *
 * The class is named FileHelper as opposed to FileUtil to avoid confusion
 * with the FileUtil class that Apache provides for hadoop.
 */
public class FileHelper {
  private static final Logger sLogger = Logger.getLogger(FileHelper.class);

  /**
   * Create a local temporary directory.
   *
   * This function uses the system temporary directory. To control the
   * location of the system directory you can set the VM argument
   * java.io.tmpdir.
   *
   * e.g -Djava.io.tmpdir=/some/other/tmpdir
   * in which case the created directory will be a sub directory of
   * /some/other/tmpdir
   *
   * @return
   */
  static public File createLocalTempDir() {
    // TODO(jlewi): Is there a java function we could use?
    File temp = null;
    try {
      temp = File.createTempFile("temp", Long.toString(System.nanoTime()));
    } catch (IOException exception) {
      sLogger.fatal("Could not create temporary file.", exception);
      System.exit(-1);
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

  /**
   * Function moves the contents of old_path into new_path. This is used
   * to save the final graph.
   * @param oldPath
   * @param newPath
   */
  static public void moveDirectoryContents(
      Configuration conf, String oldPath, String newPath) {
    // We can't invoke rename directly on old path because it ends up
    // making old_path a subdirectory of new_path.
    FileSystem fs = null;
    try{
      fs = FileSystem.get(conf);
    } catch (IOException e) {
      throw new RuntimeException("Can't get filesystem: " + e.getMessage());
    }
    try {
      Path oldPathObject = new Path(oldPath);
      for (FileStatus status : fs.listStatus(oldPathObject)) {
        Path oldFile = status.getPath();
        Path newFile = new Path(newPath, oldFile.getName());
        fs.rename(oldFile, newFile);
      }
    } catch (IOException e) {
      throw new RuntimeException("Problem moving the files: " + e.getMessage());
    }
  }

  /**
   * Find files matching the glob expression.
   *
   * This only works for the local/non hadoop filesystem.
   * @param glob
   * @return
   */
  public static ArrayList<String> matchFiles(String glob) {
    // We assume glob is a directory + a wild card expression
    // e.g /some/dir/*.fastq
    File dir = new File(FilenameUtils.getFullPath(glob));
    String pattern = FilenameUtils.getName(glob);
    FileFilter fileFilter = new WildcardFileFilter(pattern);

    File[] files =  dir.listFiles(fileFilter);
    ArrayList<String> result = new ArrayList<String>();

    if (files == null || files.length == 0) {
      return result;
    }

    for (File file : files) {
      result.add(file.getPath());
    }
    return result;
  }
}
