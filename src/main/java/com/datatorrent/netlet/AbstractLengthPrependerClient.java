/*
 *  Copyright (c) 2012-2013 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.netlet;

import com.datatorrent.common.util.DTThrowable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.common.util.VarInt;
import java.net.ConnectException;

/**
 * <p>Abstract AbstractLengthPrependerClient class.</p>
 *
 * @author Chetan Narsude <chetan@datatorrent.com>
 * @since 0.3.2
 */
public abstract class AbstractLengthPrependerClient extends com.datatorrent.netlet.AbstractClient
{
  protected byte[] buffer;
  protected ByteBuffer byteBuffer;
  protected int size, writeOffset, readOffset;

  public AbstractLengthPrependerClient()
  {
    this(new byte[64 * 1024], 0, 1024);
  }

  public AbstractLengthPrependerClient(int readBufferSize, int sendBufferSize)
  {
    this(new byte[readBufferSize], 0, sendBufferSize);
  }

  public AbstractLengthPrependerClient(byte[] readbuffer, int position, int sendBufferSize)
  {
    super(sendBufferSize);
    buffer = readbuffer;
    byteBuffer = ByteBuffer.wrap(readbuffer);
    byteBuffer.position(position);
    writeOffset = position;
    readOffset = position;
  }

  @Override
  public ByteBuffer buffer()
  {
    return byteBuffer;
  }

  public int readSize()
  {
    if (readOffset < writeOffset) {
      int offset = readOffset;

      byte tmp = buffer[readOffset++];
      if (tmp >= 0) {
        return tmp;
      }
      else if (readOffset < writeOffset) {
        int integer = tmp & 0x7f;
        tmp = buffer[readOffset++];
        if (tmp >= 0) {
          return integer | tmp << 7;
        }
        else if (readOffset < writeOffset) {
          integer |= (tmp & 0x7f) << 7;
          tmp = buffer[readOffset++];

          if (tmp >= 0) {
            return integer | tmp << 14;
          }
          else if (readOffset < writeOffset) {
            integer |= (tmp & 0x7f) << 14;
            tmp = buffer[readOffset++];
            if (tmp >= 0) {
              return integer | tmp << 21;
            }
            else if (readOffset < writeOffset) {
              integer |= (tmp & 0x7f) << 21;
              tmp = buffer[readOffset++];
              if (tmp >= 0) {
                return integer | tmp << 28;
              }
              else {
                throw new NumberFormatException("Invalid varint at location " + offset + " => "
                                                + Arrays.toString(Arrays.copyOfRange(buffer, offset, readOffset)));
              }
            }
          }
        }
      }

      readOffset = offset;
    }
    return -1;
  }

  /**
   * Upon reading the data from the socket into the byteBuffer, this method is called.
   * read is pronounced "RED", past tense of "read", and not to be confused with the opposite of the "write" method
   *
   * @param len - length of the data in number of bytes read into the byteBuffer during the most recent read.
   */
  @Override
  public void read(int len)
  {
    beginMessage();
    writeOffset += len;
    do {
      while (size == 0) {
        size = readSize();
        if (size == -1) {
          if (writeOffset == buffer.length) {
            if (readOffset > writeOffset - 5) {
              //logger.info("hit the boundary while reading varint! on {} and {}", this, readOffset);
              /*
               * we may be reading partial varint, adjust the buffers so that we have enough space to read the full data.
               */
              byte[] newArray = new byte[buffer.length];
              System.arraycopy(buffer, readOffset, newArray, 0, writeOffset - readOffset);
              buffer = newArray;
              writeOffset -= readOffset;
              readOffset = 0;
              byteBuffer = ByteBuffer.wrap(buffer);
              byteBuffer.position(writeOffset);
            }
          }
          size = 0;
          endMessage();
          return;
        }
      }

      if (writeOffset - readOffset >= size) {
        onMessage(buffer, readOffset, size);
        readOffset += size;
        size = 0;
      }
      else if (writeOffset == buffer.length) {
        if (size > buffer.length) {
          int newsize = buffer.length;
          while (newsize < size) {
            newsize <<= 1;
          }
          //logger.info("resizing buffer to size {} from size {}", newsize, buffer.length);
          byte[] newArray = new byte[newsize];
          System.arraycopy(buffer, readOffset, newArray, 0, writeOffset - readOffset);
          buffer = newArray;
          writeOffset -= readOffset;
          readOffset = 0;
          byteBuffer = ByteBuffer.wrap(newArray);
          byteBuffer.position(writeOffset);
        }
        else {
          byte[] newArray = new byte[buffer.length];
          System.arraycopy(buffer, readOffset, newArray, 0, writeOffset - readOffset);
          buffer = newArray;
          writeOffset -= readOffset;
          readOffset = 0;
          byteBuffer = ByteBuffer.wrap(buffer);
          byteBuffer.position(writeOffset);
        }
        endMessage();
        return;
      }
      else {       /* need to read more */
        endMessage();
        return;
      }
    }
    while (true);
  }

  public boolean write(byte[] message)
  {
    return write(message, 0, message.length);
  }

  public boolean write(byte[] message1, byte[] message2)
  {
    if (sendBuffer4Offers.remainingCapacity() < 3 && sendBuffer4Offers.capacity() == MAX_SENDBUFFER_SIZE) {
      logger.debug("sendBuffer for Offers = {}, socket = {}", sendBuffer4Offers, key.channel());
      return false;
    }

    if (intOffset > INT_ARRAY_SIZE) {
      intBuffer = new byte[INT_ARRAY_SIZE + 5];
      intOffset = 0;
    }

    int newOffset = VarInt.write(message1.length + message2.length, intBuffer, intOffset);
    if (send(intBuffer, intOffset, newOffset - intOffset)) {
      intOffset = newOffset;
      return send(message1, 0, message1.length) && send(message2, 0, message2.length);
    }

    return false;
  }

  private int intOffset;
  private static final int INT_ARRAY_SIZE = 4096 - 5;
  private byte[] intBuffer = new byte[INT_ARRAY_SIZE + 5];

  public boolean write(byte[] message, int offset, int size)
  {
    if (sendBuffer4Offers.remainingCapacity() < 2 && sendBuffer4Offers.capacity() == MAX_SENDBUFFER_SIZE) {
      logger.debug("sendBuffer for Offers = {}, socket = {}", sendBuffer4Offers, key.channel());
      return false;
    }

    if (intOffset > INT_ARRAY_SIZE) {
      intBuffer = new byte[INT_ARRAY_SIZE + 5];
      intOffset = 0;
    }

    int newOffset = VarInt.write(size, intBuffer, intOffset);
    if (send(intBuffer, intOffset, newOffset - intOffset)) {
      intOffset = newOffset;
      return send(message, offset, size);
    }

    return false;
  }

  @Override
  public void handleException(Exception exception, DefaultEventLoop el)
  {
    if (exception instanceof ConnectException) {
      logger.warn("Connection failed.", exception);
    }
    else if (exception instanceof IOException) {
      logger.debug("Disconnection worthy exception.", exception);
      el.disconnect(this);
    }
    else {
      DTThrowable.rethrow(exception);
    }
  }

  public void beginMessage()
  {
  }

  public abstract void onMessage(byte[] buffer, int offset, int size);

  public void endMessage()
  {
  }

  private static final Logger logger = LoggerFactory.getLogger(AbstractLengthPrependerClient.class);
}