/*
 * ProGuardCORE -- library to process Java bytecode.
 *
 * Copyright (c) 2002-2020 Guardsquare NV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package proguard.io;

import java.io.*;
import java.util.zip.*;
import proguard.classfile.TypeConstants;

/**
 * This {@link DataEntry} represents a ZIP entry.
 *
 * @author Eric Lafortune
 */
public class ZipFileDataEntry implements DataEntry {
  private final DataEntry parent;
  private final ZipEntry zipEntry;
  private ZipFile zipFile;
  private InputStream zipInputStream;
  private InputStream bufferedInputStream;

  public ZipFileDataEntry(DataEntry parent, ZipEntry zipEntry, ZipFile zipFile) {
    this.parent = parent;
    this.zipEntry = zipEntry;
    this.zipFile = zipFile;
  }

  // Implementations for DataEntry.

  @Override
  public String getName() {
    // Get the right separators.
    String name = zipEntry.getName().replace(File.separatorChar, TypeConstants.PACKAGE_SEPARATOR);

    // Chop the trailing directory slash, if any.
    int length = name.length();
    return length > 0 && name.charAt(length - 1) == TypeConstants.PACKAGE_SEPARATOR
        ? name.substring(0, length - 1)
        : name;
  }

  @Override
  public String getOriginalName() {
    return getName();
  }

  /**
   * Returns the approximate size of this zip file entry. Specifically, this returns the maximum of
   * the entry's uncompressed and compressed sizes.
   *
   * <p>NB: For small entry sizes and incompressible data, this can lead to unexpected results, such
   * as the returned result being larger than the logical/uncompressed size of the entry.
   */
  @Override
  public long getSize() {
    // Try to get some estimate of the size.
    return Math.max(zipEntry.getSize(), zipEntry.getCompressedSize());
  }

  /** Returns the uncompressed size of the underlying zip entry. */
  public long getUncompressedSize() {
    return zipEntry.getSize();
  }

  @Override
  public boolean isDirectory() {
    return zipEntry.isDirectory();
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (zipInputStream == null) {
      zipInputStream = zipFile.getInputStream(zipEntry);
    }

    if (bufferedInputStream == null) {
      bufferedInputStream = new BufferedInputStream(zipInputStream);
    }

    return bufferedInputStream;
  }

  @Override
  public void closeInputStream() throws IOException {
    zipInputStream.close();
    zipFile = null;
    bufferedInputStream = null;
  }

  @Override
  public DataEntry getParent() {
    return parent;
  }

  // Implementations for Object.

  @Override
  public String toString() {
    return parent.toString() + ':' + getName();
  }
}
