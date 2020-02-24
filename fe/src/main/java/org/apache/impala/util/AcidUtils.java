// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.impala.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.errorprone.annotations.Immutable;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.common.ValidWriteIdList;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.impala.catalog.FileMetadataLoader.LoadStats;
import org.apache.impala.common.FileSystemUtil;
import org.apache.impala.thrift.TQueryOptions;
import org.apache.impala.thrift.TTransactionalType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * Contains utility functions for working with Acid tables.
 * <p>
 * The code is mostly copy pasted from Hive. Ideally we should use the
 * code directly from Hive.
 * </p>
 */
public class AcidUtils {
  // Constant also defined in TransactionalValidationListener
  public static final String INSERTONLY_TRANSACTIONAL_PROPERTY = "insert_only";
  // Constant also defined in hive_metastoreConstants
  public static final String TABLE_IS_TRANSACTIONAL = "transactional";
  public static final String TABLE_TRANSACTIONAL_PROPERTIES = "transactional_properties";

  /**
   * Transaction parameters needed for single table operations.
   */
  public static class TblTransaction {
    public long txnId;
    public boolean ownsTxn;
    public long writeId;
    public String validWriteIds;
  }

  // Regex pattern for files in base directories. The pattern matches strings like
  // "base_0000005/abc.txt",
  // "base_0000005/0000/abc.txt",
  // "base_0000003_v0003217/000000_0"
  private static final Pattern BASE_PATTERN = Pattern.compile(
      "base_" +
      "(?<writeId>\\d+)" +
      "(?:_v(?<visibilityTxnId>\\d+))?" +
      "(?:/.*)?");

  // Regex pattern for files in delta directories. The pattern matches strings like
  // "delta_0000006_0000006/000000_0",
  // "delta_0000009_0000009_0000/0000/def.txt"
  private static final String DELTA_STR =
      "delta_" +
      "(?<minWriteId>\\d+)_" +
      "(?<maxWriteId>\\d+)" +
      "(?:_(?<optionalStatementId>\\d+)|_v(?<visibilityTxnId>\\d+))?" +
      // Optional path suffix.
      "(?:/.*)?";

  private static final Pattern DELTA_PATTERN = Pattern.compile(DELTA_STR);

  // Regex pattern for files in delete delta directories. The pattern is similar to
  // the 'DELTA_PATTERN', but starts with "delete_".
  private static final Pattern DELETE_DELTA_PATTERN = Pattern.compile(
    "delete_" + DELTA_STR);

  @VisibleForTesting
  static final long SENTINEL_BASE_WRITE_ID = Long.MIN_VALUE;

  // The code is same as what exists in AcidUtils.java in hive-exec.
  // Ideally we should move the AcidUtils code from hive-exec into
  // hive-standalone-metastore or some other jar and use it here.
  private static boolean isInsertOnlyTable(Map<String, String> props) {
    Preconditions.checkNotNull(props);
    if (!isTransactionalTable(props)) {
      return false;
    }
    String transactionalProp = props.get(TABLE_TRANSACTIONAL_PROPERTIES);
    return transactionalProp != null && INSERTONLY_TRANSACTIONAL_PROPERTY.
        equalsIgnoreCase(transactionalProp);
  }

  public static boolean isTransactionalTable(Map<String, String> props) {
    Preconditions.checkNotNull(props);
    String tableIsTransactional = props.get(TABLE_IS_TRANSACTIONAL);
    if (tableIsTransactional == null) {
      tableIsTransactional = props.get(TABLE_IS_TRANSACTIONAL.toUpperCase());
    }
    return tableIsTransactional != null && tableIsTransactional.equalsIgnoreCase("true");
  }

  public static boolean isFullAcidTable(Map<String, String> props) {
    return isTransactionalTable(props) && !isInsertOnlyTable(props);
  }

  // Sets transaction related table properties for new tables based on manually
  // set table properties and default transactional type.
  public static void setTransactionalProperties(Map<String, String> props,
      TTransactionalType defaultTransactionalType) {
    Preconditions.checkNotNull(props);
    if (props.get(TABLE_IS_TRANSACTIONAL) != null
        || props.get(TABLE_TRANSACTIONAL_PROPERTIES) != null) {
      // Table properties are set manually, ignore default.
      return;
    }

    switch (defaultTransactionalType) {
      case NONE: break;
      case INSERT_ONLY:
        props.put(TABLE_IS_TRANSACTIONAL, "true");
        props.put(TABLE_TRANSACTIONAL_PROPERTIES, INSERTONLY_TRANSACTIONAL_PROPERTY);
        break;
    }
  }

  /**
   * Predicate that checks if the file or directory is relevant for a given WriteId list.
   * <p>
   *  <b>Must be called only for ACID table.</b>
   *  Checks that the path conforms to ACID table dir structure, and includes only
   *  directories that correspond to valid committed transactions.
   * </p>
   */
  private static class WriteListBasedPredicate implements Predicate<String> {

    private final ValidTxnList validTxnList;
    private final ValidWriteIdList writeIdList;

    WriteListBasedPredicate(ValidTxnList validTxnList, ValidWriteIdList writeIdList) {
      this.validTxnList = Preconditions.checkNotNull(validTxnList);
      this.writeIdList = Preconditions.checkNotNull(writeIdList);
    }

    public boolean test(String dirPath) {
      ParsedBase parsedBase = parseBase(dirPath);
      if (parsedBase.writeId != SENTINEL_BASE_WRITE_ID) {
        return writeIdList.isValidBase(parsedBase.writeId) &&
               isTxnValid(parsedBase.visibilityTxnId);
      } else {
        ParsedDelta pd = parseDelta(dirPath);
        if (pd != null) {
          ValidWriteIdList.RangeResponse rr =
              writeIdList.isWriteIdRangeValid(pd.minWriteId, pd.maxWriteId);
          return rr.equals(ValidWriteIdList.RangeResponse.ALL);
        }
      }
      // If it wasn't in a base or a delta directory, we should include it.
      // This allows post-upgrade tables to be read.
      // TODO(todd) add an e2e test for this.
      return true;
    }

    private boolean isTxnValid(long visibilityTxnId) {
      return visibilityTxnId == -1 || validTxnList.isTxnValid(visibilityTxnId);
    }
  }

  @Immutable
  private static final class ParsedBase {
    final long writeId;
    final long visibilityTxnId;

    ParsedBase(long writeId, long visibilityTxnId) {
      this.writeId = writeId;
      this.visibilityTxnId = visibilityTxnId;
    }
  }

  @VisibleForTesting
  static ParsedBase parseBase(String relPath) {
    Matcher baseMatcher = BASE_PATTERN.matcher(relPath);
    if (baseMatcher.matches()) {
      long writeId = Long.valueOf(baseMatcher.group("writeId"));
      long visibilityTxnId = -1;
      String visibilityTxnIdStr = baseMatcher.group("visibilityTxnId");
      if (visibilityTxnIdStr != null) {
        visibilityTxnId = Long.valueOf(visibilityTxnIdStr);
      }
      return new ParsedBase(writeId, visibilityTxnId);
    }
    return new ParsedBase(SENTINEL_BASE_WRITE_ID, -1);
  }

  @VisibleForTesting
  static long getBaseWriteId(String relPath) {
    return parseBase(relPath).writeId;
  }

  @Immutable
  private static final class ParsedDelta {
    final long minWriteId;
    final long maxWriteId;
    /**
     * Negative value indicates there was no statement id.
     */
    final long statementId;

    ParsedDelta(long minWriteId, long maxWriteId, long statementId) {
      this.minWriteId = minWriteId;
      this.maxWriteId = maxWriteId;
      this.statementId = statementId;
    }
  }

  private static ParsedDelta matcherToParsedDelta(Matcher deltaMatcher) {
    if (!deltaMatcher.matches()) {
      return null;
    }
    long minWriteId = Long.valueOf(deltaMatcher.group("minWriteId"));
    long maxWriteId = Long.valueOf(deltaMatcher.group("maxWriteId"));
    String statementIdStr = deltaMatcher.group("optionalStatementId");
    long statementId = statementIdStr != null ? Long.valueOf(statementIdStr) : -1;
    return new ParsedDelta(minWriteId, maxWriteId, statementId);
  }

  private static ParsedDelta parseDelta(String dirPath) {
    return matcherToParsedDelta(DELTA_PATTERN.matcher(dirPath));
  }

  private static ParsedDelta parseDeleteDelta(String dirPath) {
    return matcherToParsedDelta(DELETE_DELTA_PATTERN.matcher(dirPath));
  }

  /**
   * Filters the files based on Acid state.
   * @param stats the FileStatuses obtained from recursively listing the directory
   * @param baseDir the base directory for the partition (or table, in the case of
   *   unpartitioned tables)
   * @param writeIds the valid write IDs for the table
   * @param loadStats stats to add counts of skipped files to. May be null.
   * @return the FileStatuses that is a subset of passed in descriptors that
   *    must be used.
   * @throws MetaException on ACID error. TODO: Remove throws clause once IMPALA-9042
   * is resolved.
   */
  public static List<FileStatus> filterFilesForAcidState(List<FileStatus> stats,
      Path baseDir, ValidTxnList validTxnList, ValidWriteIdList writeIds,
      @Nullable LoadStats loadStats) throws MetaException {
    List<FileStatus> validStats = new ArrayList<>(stats);

    // First filter out any paths that are not considered valid write IDs.
    // At the same time, calculate the max valid base write ID.
    Predicate<String> pred = new WriteListBasedPredicate(validTxnList, writeIds);
    long maxBaseWriteId = Long.MIN_VALUE;
    for (Iterator<FileStatus> it = validStats.iterator(); it.hasNext(); ) {
      FileStatus stat = it.next();
      String relPath = FileSystemUtil.relativizePath(stat.getPath(), baseDir);
      if (!pred.test(relPath)) {
        it.remove();
        if (loadStats != null) loadStats.uncommittedAcidFilesSkipped++;
        continue;
      }

      maxBaseWriteId = Math.max(getBaseWriteId(relPath), maxBaseWriteId);
    }

    // Filter out any files that are superceded by the latest valid base,
    // as well as any directories.
    for (Iterator<FileStatus> it = validStats.iterator(); it.hasNext(); ) {
      FileStatus stat = it.next();

      if (stat.isDirectory()) {
        it.remove();
        continue;
      }

      String relPath = FileSystemUtil.relativizePath(stat.getPath(), baseDir);
      long baseWriteId = getBaseWriteId(relPath);
      if (baseWriteId != SENTINEL_BASE_WRITE_ID) {
        if (baseWriteId < maxBaseWriteId) {
          it.remove();
          if (loadStats != null) loadStats.filesSupercededByNewerBase++;
        }
        continue;
      }
      ParsedDelta parsedDelta = parseDelta(relPath);
      if (parsedDelta != null) {
        if (parsedDelta.minWriteId <= maxBaseWriteId) {
          it.remove();
          if (loadStats != null) loadStats.filesSupercededByNewerBase++;
        } else if (parsedDelta.minWriteId != parsedDelta.maxWriteId) {
          // TODO(IMPALA-9512): Validate rows in minor compacted deltas.
          // We could read the non-compacted delta directories, but we'd need to check
          // that all of them still exists. Let's throw an error on minor compacted tables
          // for now since we want to read minor compacted deltas in the near future.
          throw new MetaException("Table is minor compacted which is not supported " +
              "by Impala. Run major compaction to resolve this.");
        }
        continue;
      }
      ParsedDelta deleteDelta = parseDeleteDelta(relPath);
      if (deleteDelta != null) {
        if (deleteDelta.maxWriteId > maxBaseWriteId) {
          throw new MetaException("Table has deleted rows. It's currently not " +
              "supported by Impala. Run major compaction to resolve this.");
        }
      }

      // Not in a base or a delta directory. In that case, it's probably a post-upgrade
      // file.
      // If there is no valid base: we should read the file (assuming that
      // hive.mm.allow.originals == true)
      // If there is a valid base: the file should be merged to the base by the
      // compaction, so we can assume that the file is no longer valid and just
      // waits to be deleted.
      if (maxBaseWriteId != SENTINEL_BASE_WRITE_ID) it.remove();
    }
    return validStats;
  }
}
