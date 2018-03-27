package de.uni_koblenz.west.koral.master.graph_cover_creator.impl;

import de.uni_koblenz.west.koral.common.io.EncodedFileInputStream;
import de.uni_koblenz.west.koral.common.io.EncodedFileOutputStream;
import de.uni_koblenz.west.koral.common.io.EncodedLongFileInputStream;
import de.uni_koblenz.west.koral.common.io.EncodedLongFileOutputStream;
import de.uni_koblenz.west.koral.common.io.EncodingFileFormat;
import de.uni_koblenz.west.koral.common.io.LongOutputWriter;
import de.uni_koblenz.west.koral.common.io.Statement;
import de.uni_koblenz.west.koral.common.measurement.MeasurementCollector;
import de.uni_koblenz.west.koral.common.utils.Deleter;
import de.uni_koblenz.west.koral.common.utils.NumberConversion;
import de.uni_koblenz.west.koral.master.dictionary.DictionaryEncoder;
import de.uni_koblenz.west.koral.master.utils.FixedSizeLongArrayComparator;
import de.uni_koblenz.west.koral.master.utils.InitialChunkProducer;
import de.uni_koblenz.west.koral.master.utils.IterableSortedLongArrayList;
import de.uni_koblenz.west.koral.master.utils.LongIterator;
import de.uni_koblenz.west.koral.master.utils.Merger;
import de.uni_koblenz.west.koral.master.utils.NWayMergeSort;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.logging.Logger;

/**
 * Creates a molecule hash cover.
 * 
 * @author Daniel Janke &lt;danijankATuni-koblenz.de&gt;
 *
 */
public class MoleculeHashCoverCreator extends GraphCoverCreatorBase {

  private static final int MAX_NUMBER_OF_OPEN_FILES = 100;

  private static final long MAX_CASH_SIZE = 0x80_00_00L;

  public MoleculeHashCoverCreator(Logger logger, MeasurementCollector measurementCollector) {
    super(logger, measurementCollector);
  }

  @Override
  public EncodingFileFormat getRequiredInputEncoding() {
    return EncodingFileFormat.EEE;
  }

  @Override
  protected void createCover(DictionaryEncoder dictionary, EncodedFileInputStream input,
          int numberOfGraphChunks, EncodedFileOutputStream[] outputs, boolean[] writtenFiles,
          File workingDir) {
    File internalWorkingDir = new File(
            workingDir + File.separator + this.getClass().getSimpleName());
    if (!internalWorkingDir.exists()) {
      internalWorkingDir.mkdirs();
    }

    File adjacencyOutListsSortedByVertexId = createAdjacencyListsSortedByStartVertexId(input,
            internalWorkingDir, MoleculeHashCoverCreator.MAX_NUMBER_OF_OPEN_FILES,
            MoleculeHashCoverCreator.MAX_CASH_SIZE);

    try {
      // FIXME introduce parameter n to stop exploration
      // initialize with vertices that have an indegree 0
      /*
       * (startVertexID, outDegree, (outEdge, endVertexId)*)* sorted by
       * startVertexID
       */
      File nextAdjacencyListSortedByVertexId = File.createTempFile("adjacencyList", "",
              internalWorkingDir);
      long remainingVerticesNumber = 0;
      IterableSortedLongArrayList currentFrontier = new IterableSortedLongArrayList(2,
              new FixedSizeLongArrayComparator(true, 0), MoleculeHashCoverCreator.MAX_CASH_SIZE / 2,
              internalWorkingDir, MoleculeHashCoverCreator.MAX_NUMBER_OF_OPEN_FILES);
      try (EncodedLongFileOutputStream nextAdjacencyListOut = new EncodedLongFileOutputStream(
              nextAdjacencyListSortedByVertexId);
              EncodedLongFileInputStream adjacencyInput = new EncodedLongFileInputStream(
                      adjacencyOutListsSortedByVertexId);) {
        LongIterator iterator = adjacencyInput.iterator();
        while (iterator.hasNext()) {
          long startVertexId = iterator.next();
          long inDegree = iterator.next();
          long outDegree = iterator.next();
          if (outDegree == 0) {
            // ignore vertices with an empty out degree
            continue;
          }
          if (inDegree == 0) {
            int chunkIndex = getChunkIndex(startVertexId, numberOfGraphChunks);
            for (int i = 0; i < outDegree; i++) {
              long edgeId = iterator.next();
              long endVertexId = iterator.next();
              Statement stmt = Statement.getStatement(EncodingFileFormat.EEE,
                      NumberConversion.long2bytes(startVertexId),
                      NumberConversion.long2bytes(edgeId), NumberConversion.long2bytes(endVertexId),
                      getContainment(numberOfGraphChunks));
              writeStatementToChunk(chunkIndex, numberOfGraphChunks, stmt, outputs, writtenFiles);
              currentFrontier.append(endVertexId, chunkIndex);
            }
          } else {
            // this vertex will be visited in the future
            nextAdjacencyListOut.writeLong(startVertexId);
            nextAdjacencyListOut.writeLong(outDegree);
            for (int i = 0; i < outDegree; i++) {
              nextAdjacencyListOut.writeLong(iterator.next());
              nextAdjacencyListOut.writeLong(iterator.next());
            }
            remainingVerticesNumber++;
          }
        }
      }
      while (remainingVerticesNumber > 0) {
        adjacencyOutListsSortedByVertexId.delete();
        adjacencyOutListsSortedByVertexId = nextAdjacencyListSortedByVertexId;
        nextAdjacencyListSortedByVertexId = File.createTempFile("adjacencyList", "",
                internalWorkingDir);
        remainingVerticesNumber = 0;
        @SuppressWarnings("resource")
        IterableSortedLongArrayList nextFrontier = new IterableSortedLongArrayList(2,
                new FixedSizeLongArrayComparator(true, 0),
                MoleculeHashCoverCreator.MAX_CASH_SIZE / 2, internalWorkingDir,
                MoleculeHashCoverCreator.MAX_NUMBER_OF_OPEN_FILES);
        try (EncodedLongFileOutputStream nextAdjacencyListOut = new EncodedLongFileOutputStream(
                nextAdjacencyListSortedByVertexId);
                EncodedLongFileInputStream adjacencyInput = new EncodedLongFileInputStream(
                        adjacencyOutListsSortedByVertexId);) {
          LongIterator adjacencyIterator = adjacencyInput.iterator();
          if (currentFrontier.isEmpty()) {
            // find new seed
            if (!adjacencyIterator.hasNext()) {
              break;
            }
            long startVertexId = adjacencyIterator.next();
            int chunkIndex = getChunkIndex(startVertexId, numberOfGraphChunks);
            long outDegree = adjacencyIterator.next();
            for (int i = 0; i < outDegree; i++) {
              long edgeId = adjacencyIterator.next();
              long endVertexId = adjacencyIterator.next();
              Statement stmt = Statement.getStatement(EncodingFileFormat.EEE,
                      NumberConversion.long2bytes(startVertexId),
                      NumberConversion.long2bytes(edgeId), NumberConversion.long2bytes(endVertexId),
                      getContainment(numberOfGraphChunks));
              writeStatementToChunk(chunkIndex, numberOfGraphChunks, stmt, outputs, writtenFiles);
              currentFrontier.append(endVertexId, chunkIndex);
            }
          }
          LongIterator frontierIterator = currentFrontier.iterator();
          while (adjacencyIterator.hasNext() && frontierIterator.hasNext()) {
            long frontierVertex = frontierIterator.next();
            long frontierChunk = frontierIterator.next();

            long vertexId = adjacencyIterator.next();
            // search for a match
            while (adjacencyIterator.hasNext() && frontierIterator.hasNext()
                    && (vertexId != frontierVertex)) {
              if (frontierVertex < vertexId) {
                frontierVertex = frontierIterator.next();
                frontierChunk = frontierIterator.next();
              } else if (vertexId < frontierVertex) {
                // skip unmatched vertices
                long outDegree = adjacencyIterator.next();
                nextAdjacencyListOut.writeLong(vertexId);
                nextAdjacencyListOut.writeLong(outDegree);
                for (int i = 0; i < outDegree; i++) {
                  nextAdjacencyListOut.writeLong(adjacencyIterator.next());
                  nextAdjacencyListOut.writeLong(adjacencyIterator.next());
                }
                remainingVerticesNumber++;
                vertexId = adjacencyIterator.next();
              }
            }
            if (frontierVertex == vertexId) {
              // treat the outgoing edges
              long outDegree = adjacencyIterator.next();
              for (int i = 0; i < outDegree; i++) {
                long edgeId = adjacencyIterator.next();
                long endVertexId = adjacencyIterator.next();
                Statement stmt = Statement.getStatement(EncodingFileFormat.EEE,
                        NumberConversion.long2bytes(vertexId), NumberConversion.long2bytes(edgeId),
                        NumberConversion.long2bytes(endVertexId),
                        getContainment(numberOfGraphChunks));
                writeStatementToChunk((int) frontierChunk, numberOfGraphChunks, stmt, outputs,
                        writtenFiles);
                if (endVertexId != vertexId) {
                  // in case of self-loops do not visit vertex again
                  nextFrontier.append(endVertexId, frontierChunk);
                }
              }
            }
            adjacencyIterator.close();
            frontierIterator.close();
          }
          currentFrontier.close();
          currentFrontier = nextFrontier;
        }
      }
      currentFrontier.close();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    Deleter.deleteFolder(internalWorkingDir);
  }

  private byte[] getContainment(int numberOfGraphChunks) {
    int bitsetSize = numberOfGraphChunks / Byte.SIZE;
    if ((numberOfGraphChunks % Byte.SIZE) != 0) {
      bitsetSize += 1;
    }
    byte[] containment = new byte[bitsetSize];
    return containment;
  }

  private int getChunkIndex(long startVertexId, int numberOfGraphChunks) {
    return Long.hashCode(startVertexId) % numberOfGraphChunks;
  }

  /**
   * @param input
   * @param workingDir
   * @param maxNumberOfOpenFiles
   * @param maxCashSize
   * @return (startVertexID, inDegree, outDegree, (outEdge, endVertexId)*)*
   *         sorted by startVertexID
   */
  private File createAdjacencyListsSortedByStartVertexId(EncodedFileInputStream input,
          File workingDir, int maxNumberOfOpenFiles, long maxCashSize) {
    InitialChunkProducer producer = null;
    Merger merger = null;
    try {
      producer = new InitialChunkProducer() {

        /**
         * format {startVertexId, indegree, outdegree, (edgeId, endVertexId)?}
         */
        private long[][] elements;

        private int nextIndex;

        private final Iterator<Statement> iterator = input.iterator();

        @Override
        public void loadNextChunk() throws IOException {
          if (elements == null) {
            long numberOfElements = maxCashSize / Long.BYTES / 5;
            elements = new long[(int) numberOfElements][5];
          }
          nextIndex = 0;
          while (iterator.hasNext() && (nextIndex < (elements.length - 1))) {
            Statement stmt = iterator.next();
            elements[nextIndex][0] = stmt.getSubjectAsLong();
            elements[nextIndex][1] = 0;
            elements[nextIndex][2] = 1;
            elements[nextIndex][3] = stmt.getPropertyAsLong();
            elements[nextIndex][4] = stmt.getObjectAsLong();
            nextIndex += 1;

            elements[nextIndex][0] = stmt.getObjectAsLong();
            elements[nextIndex][1] = 1;
            elements[nextIndex][2] = 0;
            elements[nextIndex][3] = 0;
            elements[nextIndex][4] = 0;
            nextIndex += 1;
          }
        }

        @Override
        public void sort(Comparator<long[]> comparator) {
          Arrays.sort(elements, 0, nextIndex, comparator);
        }

        @Override
        public boolean hasNextChunk() {
          return nextIndex > 0;
        }

        @Override
        public void writeChunk(LongOutputWriter output) throws IOException {
          int startIndex = 0;
          for (int exclusiveEndIndex = 1; exclusiveEndIndex <= nextIndex; exclusiveEndIndex++) {
            if ((exclusiveEndIndex == elements.length)
                    || (elements[startIndex][0] != elements[exclusiveEndIndex][0])) {
              output.writeLong(elements[startIndex][0]);
              long indegree = 0;
              long outdegree = 0;
              for (int i = startIndex; i < exclusiveEndIndex; i++) {
                indegree += elements[i][1];
                outdegree += elements[i][2];
              }
              output.writeLong(indegree);
              output.writeLong(outdegree);
              for (int i = startIndex; i < exclusiveEndIndex; i++) {
                if (elements[i][2] > 0) {
                  output.writeLong(elements[i][3]);
                  output.writeLong(elements[i][4]);
                }
              }
              startIndex = exclusiveEndIndex;
            }
          }
        }

        @Override
        public void close() {
          elements = null;
        }
      };
      merger = new Merger() {

        @Override
        public void startNextMergeLevel() {
        }

        @Override
        public long[] readNextElement(LongIterator iterator) throws IOException {
          return new long[] { iterator.next(), iterator.next(), iterator.next() };
        }

        @Override
        public void mergeAndWrite(BitSet indicesOfSmallestElement, long[][] elements,
                LongIterator[] iterators, LongOutputWriter out) throws IOException {
          out.writeLong(elements[indicesOfSmallestElement.nextSetBit(0)][0]);
          long indegree = 0;
          long outdegree = 0;
          for (int i = indicesOfSmallestElement.nextSetBit(0); i >= 0; i = indicesOfSmallestElement
                  .nextSetBit(i + 1)) {
            indegree += elements[i][1];
            outdegree += elements[i][2];
          }
          out.writeLong(indegree);
          out.writeLong(outdegree);
          for (int i = indicesOfSmallestElement.nextSetBit(0); i >= 0; i = indicesOfSmallestElement
                  .nextSetBit(i + 1)) {
            for (int j = 0; j < elements[i][2]; j++) {
              out.writeLong(iterators[i].next());
              out.writeLong(iterators[i].next());
            }
          }
        }

        @Override
        public void close() {
        }
      };
      Comparator<long[]> comparator = new FixedSizeLongArrayComparator(true, 0);
      File adjacencyListSortedByVertex = File.createTempFile("adjacencyListSortedByVertex", "",
              workingDir);
      NWayMergeSort sort = new NWayMergeSort();
      sort.sort(producer, merger, comparator, workingDir, maxNumberOfOpenFiles,
              adjacencyListSortedByVertex);
      return adjacencyListSortedByVertex;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      if (producer != null) {
        producer.close();
      }
      if (merger != null) {
        merger.close();
      }
    }
  }

  @Override
  public void close() {
    super.close();
  }

}
