package de.uni_koblenz.west.koral.master.tasks;

import de.uni_koblenz.west.koral.common.ftp.FTPServer;
import de.uni_koblenz.west.koral.common.measurement.MeasurementCollector;
import de.uni_koblenz.west.koral.common.measurement.MeasurementType;
import de.uni_koblenz.west.koral.common.messages.MessageNotifier;
import de.uni_koblenz.west.koral.common.messages.MessageType;
import de.uni_koblenz.west.koral.common.messages.MessageUtils;
import de.uni_koblenz.west.koral.common.networManager.NetworkManager;
import de.uni_koblenz.west.koral.common.utils.NumberConversion;
import de.uni_koblenz.west.koral.common.utils.RDFFileIterator;
import de.uni_koblenz.west.koral.master.client_manager.ClientConnectionManager;
import de.uni_koblenz.west.koral.master.dictionary.DictionaryEncoder;
import de.uni_koblenz.west.koral.master.graph_cover_creator.CoverStrategyType;
import de.uni_koblenz.west.koral.master.graph_cover_creator.GraphCoverCreator;
import de.uni_koblenz.west.koral.master.graph_cover_creator.GraphCoverCreatorFactory;
import de.uni_koblenz.west.koral.master.graph_cover_creator.NHopReplicator;
import de.uni_koblenz.west.koral.master.statisticsDB.GraphStatistics;
import de.uni_koblenz.west.koral.slave.KoralSlave;

import java.io.Closeable;
import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Thread that is used to load a graph, i.e.,
 * <ol>
 * <li>Requesting graph files from client.</li>
 * <li>Creating the requested graph cover.</li>
 * <li>Encoding the graph chunks and collecting statistical information.</li>
 * <li>Sending encoded graph chunks to the {@link KoralSlave}s.</li>
 * <li>Waiting for loading finished messages off all {@link KoralSlave}s.</li>
 * </ol>
 * 
 * @author Daniel Janke &lt;danijankATuni-koblenz.de&gt;
 *
 */
public class GraphLoaderTask extends Thread implements Closeable {

  private final Logger logger;

  private final MeasurementCollector measurementCollector;

  private final int clientId;

  private final ClientConnectionManager clientConnections;

  private final NetworkManager slaveConnections;

  private final DictionaryEncoder dictionary;

  private final GraphStatistics statistics;

  private final File workingDir;

  private final File graphFilesDir;

  private GraphCoverCreator coverCreator;

  private int replicationPathLength;

  private int numberOfGraphChunks;

  private ClientConnectionKeepAliveTask keepAliveThread;

  private boolean graphIsLoadingOrLoaded;

  private final MessageNotifier messageNotifier;

  private final String externalFtpIpAddress;

  private final String internalFtpIpAddress;

  private final String ftpPort;

  private final FTPServer ftpServer;

  private volatile int numberOfBusySlaves;

  private boolean isStarted;

  public GraphLoaderTask(int clientID, ClientConnectionManager clientConnections,
          NetworkManager slaveConnections, String externalFtpIpAddress, String internalFtpIpAddress,
          String ftpPort, DictionaryEncoder dictionary, GraphStatistics statistics, File tmpDir,
          MessageNotifier messageNotifier, Logger logger, MeasurementCollector collector) {
    setDaemon(true);
    graphIsLoadingOrLoaded = true;
    isStarted = false;
    clientId = clientID;
    this.clientConnections = clientConnections;
    this.slaveConnections = slaveConnections;
    this.dictionary = dictionary;
    this.statistics = statistics;
    this.messageNotifier = messageNotifier;
    this.logger = logger;
    measurementCollector = collector;
    this.externalFtpIpAddress = externalFtpIpAddress;
    this.internalFtpIpAddress = internalFtpIpAddress;
    this.ftpPort = ftpPort;
    ftpServer = new FTPServer();
    workingDir = new File(
            tmpDir.getAbsolutePath() + File.separatorChar + "koral_client_" + clientId);
    if (workingDir.exists()) {
      deleteContent(workingDir);
    } else {
      if (!workingDir.mkdirs()) {
        throw new RuntimeException(
                "The working directory " + workingDir.getAbsolutePath() + " could not be created!");
      }
    }
    graphFilesDir = new File(workingDir.getAbsolutePath() + File.separatorChar + "graphFiles");
  }

  private void deleteContent(File dir) {
    if (dir.exists()) {
      for (File file : dir.listFiles()) {
        if (file.isDirectory()) {
          deleteContent(file);
        }
        if (!file.delete()) {
          throw new RuntimeException(file.getAbsolutePath() + " could not be deleted!");
        }
      }
    }
  }

  public void loadGraph(byte[][] args, int numberOfGraphChunks) {
    if (args.length < 4) {
      throw new IllegalArgumentException(
              "Loading a graph requires at least 4 arguments, but received only " + args.length
                      + " arguments.");
    }
    CoverStrategyType coverStrategy = CoverStrategyType.values()[NumberConversion
            .bytes2int(args[0])];
    int replicationPathLength = NumberConversion.bytes2int(args[1]);
    int numberOfFiles = NumberConversion.bytes2int(args[2]);
    loadGraph(coverStrategy, replicationPathLength, numberOfGraphChunks, numberOfFiles,
            getFileExtensions(args, 3));
  }

  private String[] getFileExtensions(byte[][] args, int startIndex) {
    String[] fileExtension = new String[args.length - startIndex];
    for (int i = 0; i < fileExtension.length; i++) {
      fileExtension[i] = MessageUtils.convertToString(args[startIndex + i], logger);
    }
    return fileExtension;
  }

  public void loadGraph(CoverStrategyType coverStrategy, int replicationPathLength,
          int numberOfGraphChunks, int numberOfFiles, String[] fileExtensions) {
    if (logger != null) {
      logger.finer("loadGraph(coverStrategy=" + coverStrategy.name() + ", replicationPathLength="
              + replicationPathLength + ", numberOfFiles=" + numberOfFiles + ")");
    }
    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_START,
              System.currentTimeMillis(), coverStrategy.toString(),
              new Integer(replicationPathLength).toString(),
              new Integer(numberOfGraphChunks).toString());
    }
    coverCreator = GraphCoverCreatorFactory.getGraphCoverCreator(coverStrategy, logger,
            measurementCollector);
    this.replicationPathLength = replicationPathLength;
    this.numberOfGraphChunks = numberOfGraphChunks;
    ftpServer.start(externalFtpIpAddress, ftpPort, graphFilesDir);
    clientConnections.send(clientId, MessageUtils.createStringMessage(MessageType.MASTER_SEND_FILES,
            externalFtpIpAddress + ":" + ftpPort, logger));
    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_FILE_TRANSFER_TO_MASTER_START,
              System.currentTimeMillis());
    }
  }

  public void receiveFilesSent() {
    ftpServer.close();
    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_FILE_TRANSFER_TO_MASTER_END,
              System.currentTimeMillis());
    }
    start();
  }

  @Override
  public void run() {
    isStarted = true;
    try {
      keepAliveThread = new ClientConnectionKeepAliveTask(clientConnections, clientId);
      keepAliveThread.start();

      File[] chunks = createGraphChunks();
      File[] encodedFiles = encodeGraphFiles(chunks);

      ftpServer.start(internalFtpIpAddress, ftpPort, workingDir);
      numberOfBusySlaves = 0;
      List<GraphLoaderListener> listeners = new ArrayList<>();
      for (int i = 0; i < encodedFiles.length; i++) {
        File file = encodedFiles[i];
        if (file == null) {
          continue;
        }
        numberOfBusySlaves++;
        // slave ids start with 1!
        GraphLoaderListener listener = new GraphLoaderListener(this, i + 1);
        listeners.add(listener);
        messageNotifier.registerMessageListener(GraphLoaderListener.class, listener);
        slaveConnections.sendMore(i + 1, new byte[] { MessageType.START_FILE_TRANSFER.getValue() });
        slaveConnections.sendMore(i + 1, (internalFtpIpAddress + ":" + ftpPort).getBytes("UTF-8"));
        slaveConnections.send(i + 1, file.getName().getBytes("UTF-8"));
      }

      while (!isInterrupted() && (numberOfBusySlaves > 0)) {
        long currentTime = System.currentTimeMillis();
        long timeToSleep = 100 - (System.currentTimeMillis() - currentTime);
        if (!isInterrupted() && (timeToSleep > 0)) {
          try {
            Thread.sleep(timeToSleep);
          } catch (InterruptedException e) {
            break;
          }
        }
      }

      for (GraphLoaderListener listener : listeners) {
        messageNotifier.unregisterMessageListener(GraphLoaderListener.class, listener);
      }

      keepAliveThread.interrupt();

      if (numberOfBusySlaves == 0) {
        clientConnections.send(clientId,
                new byte[] { MessageType.CLIENT_COMMAND_SUCCEEDED.getValue() });
      } else {
        clientConnections.send(clientId,
                MessageUtils.createStringMessage(MessageType.CLIENT_COMMAND_FAILED,
                        "Loading of graph was interrupted before all slaves have loaded the graph.",
                        logger));
      }
    } catch (Throwable e) {
      clearDatabase();
      if (logger != null) {
        logger.throwing(e.getStackTrace()[0].getClassName(), e.getStackTrace()[0].getMethodName(),
                e);
      }
      clientConnections.send(clientId,
              MessageUtils.createStringMessage(MessageType.CLIENT_COMMAND_FAILED,
                      e.getClass().getName() + ":" + e.getMessage(), logger));
      close();
    }
  }

  public void processSlaveResponse(byte[] message) {
    MessageType messageType = MessageType.valueOf(message[0]);
    if (messageType == null) {
      if (logger != null) {
        logger.finest("Ignoring message with unknown type.");
      }
    }
    switch (messageType) {
      case GRAPH_LOADING_COMPLETE:
        numberOfBusySlaves--;
        break;
      case GRAPH_LOADING_FAILED:
        clearDatabase();
        String errorMessage = null;
        try {
          errorMessage = new String(message, 3, message.length, "UTF-8");
        } catch (UnsupportedEncodingException e) {
          errorMessage = e.getMessage();
        }
        if (logger != null) {
          logger.finer("Loading of graph failed on slave "
                  + NumberConversion.bytes2short(message, 1) + ". Reason: " + errorMessage);
        }
        clientConnections.send(clientId, MessageUtils.createStringMessage(
                MessageType.CLIENT_COMMAND_FAILED, "Loading of graph failed on slave "
                        + NumberConversion.bytes2short(message, 1) + ". Reason: " + errorMessage,
                logger));
        close();
        break;
      default:
        if (logger != null) {
          logger.finest("Ignoring message of type " + messageType);
        }
    }
  }

  private void clearDatabase() {
    dictionary.clear();
    statistics.clear();
  }

  private File[] createGraphChunks() {
    if (logger != null) {
      logger.finer("creation of graph cover started");
    }
    clientConnections.send(clientId, MessageUtils.createStringMessage(
            MessageType.MASTER_WORK_IN_PROGRESS, "Started creation of graph cover.", logger));

    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_COVER_CREATION_START,
              System.currentTimeMillis());
    }
    RDFFileIterator rdfFiles = new RDFFileIterator(graphFilesDir, true, logger);
    File[] chunks = coverCreator.createGraphCover(rdfFiles, workingDir, numberOfGraphChunks);
    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_COVER_CREATION_END,
              System.currentTimeMillis());
    }

    if (replicationPathLength != 0) {
      if (measurementCollector != null) {
        measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_NHOP_REPLICATION_START,
                System.currentTimeMillis());
      }
      NHopReplicator replicator = new NHopReplicator(logger, measurementCollector);
      chunks = replicator.createNHopReplication(chunks, workingDir, replicationPathLength);
      if (measurementCollector != null) {
        measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_NHOP_REPLICATION_END,
                System.currentTimeMillis());
      }
    }

    if (logger != null) {
      logger.finer("creation of graph cover finished");
    }
    clientConnections.send(clientId, MessageUtils.createStringMessage(
            MessageType.MASTER_WORK_IN_PROGRESS, "Finished creation of graph cover.", logger));
    return chunks;
  }

  private File[] encodeGraphFiles(File[] plainGraphChunks) {
    if (logger != null) {
      logger.finer("encoding of graph chunks");
    }
    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_ENCODING_START,
              System.currentTimeMillis());
    }
    clientConnections.send(clientId, MessageUtils.createStringMessage(
            MessageType.MASTER_WORK_IN_PROGRESS, "Started encoding of graph chunks.", logger));
    File[] encodedFiles = dictionary.encodeGraphChunks(plainGraphChunks, statistics, workingDir);
    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_ENCODING_END,
              System.currentTimeMillis());
    }
    if (logger != null) {
      logger.finer("encoding of graph chunks finished");
    }
    clientConnections.send(clientId, MessageUtils.createStringMessage(
            MessageType.MASTER_WORK_IN_PROGRESS, "Finished encoding of graph chunks.", logger));
    return encodedFiles;
  }

  public boolean isGraphLoadingOrLoaded() {
    return graphIsLoadingOrLoaded;
  }

  @Override
  public void close() {
    coverCreator.close();
    if (isAlive()) {
      interrupt();
      clientConnections.send(clientId,
              MessageUtils.createStringMessage(MessageType.CLIENT_COMMAND_FAILED,
                      "GraphLoaderTask has been closed before it could finish.", logger));
      graphIsLoadingOrLoaded = false;
    } else if (!isStarted) {
      graphIsLoadingOrLoaded = false;
    }
    if ((keepAliveThread != null) && keepAliveThread.isAlive()) {
      keepAliveThread.interrupt();
    }
    ftpServer.close();
    deleteContent(graphFilesDir);
    graphFilesDir.delete();
    deleteContent(workingDir);
    workingDir.delete();
    if (measurementCollector != null) {
      measurementCollector.measureValue(MeasurementType.LOAD_GRAPH_FINISHED,
              System.currentTimeMillis());
    }
  }

}