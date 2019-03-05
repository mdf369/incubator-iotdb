package org.apache.iotdb.tsfile.write.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iotdb.tsfile.common.conf.TSFileConfig;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.encoding.decoder.Decoder;
import org.apache.iotdb.tsfile.file.MetaMarker;
import org.apache.iotdb.tsfile.file.footer.ChunkGroupFooter;
import org.apache.iotdb.tsfile.file.header.ChunkHeader;
import org.apache.iotdb.tsfile.file.header.PageHeader;
import org.apache.iotdb.tsfile.file.metadata.ChunkGroupMetaData;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetaData;
import org.apache.iotdb.tsfile.file.metadata.TsDeviceMetadata;
import org.apache.iotdb.tsfile.file.metadata.TsDeviceMetadataIndex;
import org.apache.iotdb.tsfile.file.metadata.TsFileMetaData;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.file.metadata.statistics.FloatStatistics;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.BatchData;
import org.apache.iotdb.tsfile.read.reader.page.PageReader;
import org.apache.iotdb.tsfile.write.TsFileWriter;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.record.datapoint.FloatDataPoint;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class NativeRestorableIOWriterTest {

  private static final String FILE_NAME = "test.ts";

  @Test
  public void testOnlyHeadMagic() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    assertEquals(TsFileIOWriter.magicStringBytes.length, rWriter.getTruncatedPosition());
    assertTrue(file.delete());

  }

  @Test
  public void testOnlyFirstMask() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    //we have to flush using inner API.
    writer.getIOWriter().out.write(new byte[] {MetaMarker.CHUNK_HEADER});
    writer.getIOWriter().forceClose();
    assertEquals(TsFileIOWriter.magicStringBytes.length + 1, file.length());
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    assertEquals(TsFileIOWriter.magicStringBytes.length, rWriter.getTruncatedPosition());
    assertTrue(file.delete());
  }

  @Test
  public void testOnlyOneIncompleteChunkHeader() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);

    ChunkHeader header = new ChunkHeader("s1", 100, TSDataType.FLOAT, CompressionType.SNAPPY,
        TSEncoding.PLAIN, 5);
    ByteBuffer buffer = ByteBuffer.allocate(header.getSerializedSize());
    header.serializeTo(buffer);
    buffer.flip();
    byte[] data = new byte[3];
    buffer.get(data, 0, 3);
    writer.getIOWriter().out.write(data);
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    assertEquals(TsFileIOWriter.magicStringBytes.length, rWriter.getTruncatedPosition());
    assertTrue(file.delete());
  }

  @Test
  public void testOnlyOneChunkHeader() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.getIOWriter()
        .startFlushChunk(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.PLAIN),
            CompressionType.SNAPPY, TSDataType.FLOAT,
            TSEncoding.PLAIN, new FloatStatistics(), 100, 50, 100, 10);
    writer.getIOWriter().forceClose();

    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    assertEquals(TsFileIOWriter.magicStringBytes.length, rWriter.getTruncatedPosition());
    assertTrue(file.delete());
  }

  @Test
  public void testOnlyOneChunkHeaderAndSomePage() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.addMeasurement(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
    writer.addMeasurement(new MeasurementSchema("s2", TSDataType.FLOAT, TSEncoding.RLE));
    writer.write(new TSRecord(1, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.flushForTest();
    long pos = writer.getIOWriter().getPos();
    //let's delete one byte.
    writer.getIOWriter().out.truncate(pos - 1);
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    assertEquals(TsFileIOWriter.magicStringBytes.length, rWriter.getTruncatedPosition());
    assertTrue(file.delete());
  }


  @Test
  public void testOnlyOneChunkGroup() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.addMeasurement(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
    writer.addMeasurement(new MeasurementSchema("s2", TSDataType.FLOAT, TSEncoding.RLE));
    writer.write(new TSRecord(1, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.flushForTest();
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    assertEquals(TsFileIOWriter.magicStringBytes.length, rWriter.getTruncatedPosition());
    assertTrue(file.delete());
  }

  @Test
  public void testOnlyOneChunkGroupAndOneMask() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.addMeasurement(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
    writer.addMeasurement(new MeasurementSchema("s2", TSDataType.FLOAT, TSEncoding.RLE));
    writer.write(new TSRecord(1, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.flushForTest();
    writer.getIOWriter().writeChunkMaskForTest();
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    assertNotEquals(TsFileIOWriter.magicStringBytes.length, rWriter.getTruncatedPosition());
    TsFileSequenceReader reader = new TsFileSequenceReader(FILE_NAME);
    TsDeviceMetadataIndex index = reader.readFileMetadata().getDeviceMap().get("d1");
    assertEquals(1, reader.readTsDeviceMetaData(index).getChunkGroupMetaDataList().size());
    reader.close();
    assertTrue(file.delete());
  }


  @Test
  public void testTwoChunkGroupAndMore() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.addMeasurement(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
    writer.addMeasurement(new MeasurementSchema("s2", TSDataType.FLOAT, TSEncoding.RLE));
    writer.write(new TSRecord(1, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));

    writer.write(new TSRecord(1, "d2").addTuple(new FloatDataPoint("s1", 6))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d2").addTuple(new FloatDataPoint("s1", 6))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.flushForTest();
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    TsFileSequenceReader reader = new TsFileSequenceReader(FILE_NAME);
    TsDeviceMetadataIndex index = reader.readFileMetadata().getDeviceMap().get("d1");
    assertEquals(1, reader.readTsDeviceMetaData(index).getChunkGroupMetaDataList().size());
    reader.close();
    assertTrue(file.delete());
  }

  @Test
  public void testNoSeperatorMask() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.addMeasurement(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
    writer.addMeasurement(new MeasurementSchema("s2", TSDataType.FLOAT, TSEncoding.RLE));
    writer.write(new TSRecord(1, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));

    writer.write(new TSRecord(1, "d2").addTuple(new FloatDataPoint("s1", 6))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d2").addTuple(new FloatDataPoint("s1", 6))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.flushForTest();
    writer.getIOWriter().writeSeparatorMaskForTest();
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    TsFileSequenceReader reader = new TsFileSequenceReader(FILE_NAME);
    TsDeviceMetadataIndex index = reader.readFileMetadata().getDeviceMap().get("d1");
    assertEquals(1, reader.readTsDeviceMetaData(index).getChunkGroupMetaDataList().size());
    index = reader.readFileMetadata().getDeviceMap().get("d2");
    assertEquals(1, reader.readTsDeviceMetaData(index).getChunkGroupMetaDataList().size());
    reader.close();
    assertTrue(file.delete());
  }


  @Test
  public void testHavingSomeFileMetadata() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.addMeasurement(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
    writer.addMeasurement(new MeasurementSchema("s2", TSDataType.FLOAT, TSEncoding.RLE));
    writer.write(new TSRecord(1, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));

    writer.write(new TSRecord(1, "d2").addTuple(new FloatDataPoint("s1", 6))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d2").addTuple(new FloatDataPoint("s1", 6))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.flushForTest();
    writer.getIOWriter().writeSeparatorMaskForTest();
    writer.getIOWriter().writeSeparatorMaskForTest();
    writer.getIOWriter().forceClose();
    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    writer = new TsFileWriter(rWriter);
    writer.close();
    TsFileSequenceReader reader = new TsFileSequenceReader(FILE_NAME);
    TsDeviceMetadataIndex index = reader.readFileMetadata().getDeviceMap().get("d1");
    assertEquals(1, reader.readTsDeviceMetaData(index).getChunkGroupMetaDataList().size());
    index = reader.readFileMetadata().getDeviceMap().get("d2");
    assertEquals(1, reader.readTsDeviceMetaData(index).getChunkGroupMetaDataList().size());
    reader.close();
    assertTrue(file.delete());
  }

  @Test
  public void testOpenCompleteFile() throws Exception {
    File file = new File(FILE_NAME);
    TsFileWriter writer = new TsFileWriter(file);
    writer.addMeasurement(new MeasurementSchema("s1", TSDataType.FLOAT, TSEncoding.RLE));
    writer.addMeasurement(new MeasurementSchema("s2", TSDataType.FLOAT, TSEncoding.RLE));
    writer.write(new TSRecord(1, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.write(new TSRecord(2, "d1").addTuple(new FloatDataPoint("s1", 5))
        .addTuple(new FloatDataPoint("s2", 4)));
    writer.close();

    NativeRestorableIOWriter rWriter = new NativeRestorableIOWriter(file);
    assertFalse(rWriter.canWrite());
    assertTrue(file.delete());
  }
}