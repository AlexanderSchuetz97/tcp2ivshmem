/*
 * Copyright Alexander Schütz, 2020-2022
 *
 * This file is part of tcp2ivshmem.
 *
 * tcp2ivshmem is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * tcp2ivshmem is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * A copy of the GNU General Public License should be provided
 * in the COPYING file in top level directory of tcp2ivshmem.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.alexanderschuetz97.tcp2ivshmem.ivshmem;

import io.github.alexanderschuetz97.ivshmem4j.api.InterruptServiceRoutine;
import io.github.alexanderschuetz97.ivshmem4j.api.IvshmemMemory;
import io.github.alexanderschuetz97.nativeutils.api.NativeMemory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared Memory Ring Buffer.
 * Requires Interrupts.
 * This Object is single use only. If it was used and closed then a new RingBuffer Object is required in order to
 * reuse the address for a new RingBuffer.
 */
public class RingBuffer implements Closeable {


    public static final int STATE_OFFSET = 0;

    public static final int INTERRUPT_FLAG_WRITE_OFFSET = STATE_OFFSET + 1;

    public static final int INTERRUPT_FLAG_READ_OFFSET = STATE_OFFSET + 2;

    /**
     * Offset where the vector int will be located in the SharedMemory.
     */
    public static final int VECTOR_OFFSET = 4;

    /**
     * Offset where the buffer size will be located in the Shared Memory.
     */
    public static final int SIZE_OFFSET = VECTOR_OFFSET + 4;

    /**
     * Offset where the write index will be located in the Shared Memory.
     * The write index will contain the offset relative to the BUFFER_START_OFFSET where the next byte will be written to.
     */
    public static final int WRITE_INDEX_OFFSET = SIZE_OFFSET + 8;

    /**
     * Offset where the read index will be located in the Shared Memory.
     * The read index will contain the offset relative to the BUFFER_START_OFFSET where the next byte will be read from.
     */
    public static final int READ_INDEX_OFFSET = WRITE_INDEX_OFFSET + 8;

    public static final int WRITE_PEER_OFFSET = READ_INDEX_OFFSET + 8;

    public static final int READ_PEER_OFFSET = WRITE_PEER_OFFSET + 4;

    /**
     * Amount of bytes at the start of every RingBuffer that will be used for control bytes.
     */
    public static final int OVERHEAD = READ_PEER_OFFSET + 4;

    /**
     * Offset of the actual data of the Ring Buffer.
     */
    public static final int BUFFER_START_OFFSET = OVERHEAD;

    protected static final byte STATE_UNCONNECTED = 0;

    protected static final byte STATE_CONNECTING = 1;

    protected static final byte STATE_CONNECTED = 2;

    protected static final byte STATE_CLOSED = 3;

    /**
     * The shared memory to use to write/read the bytes to/from.
     */
    protected final IvshmemMemory shmemory;

    protected final NativeMemory memory;
    /**
     * the base address where the ring buffer is located in inside the shared memory.
     */
    protected final long address;


    /**
     * The vector that is sued for this RingBuffer Object.
     */
    protected int vector = -1;


    /**
     * holds a offset relative to address that the current RingBuffer will access to either read from or write to.
     */
    protected volatile long localIndex = 0;

    /**
     * flag to indicate if the RingBuffer is closed.
     */
    protected volatile boolean closedFlag = false;


    /**
     * Lock that prevents concurrent access to the input stream.
     * Note that this lock and the interrupt lock may be held together by the same thread.
     */
    protected final ReentrantLock accessLock = new ReentrantLock();

    /**
     * Lock that provides a condition for interrupt waiting.
     */
    protected final ReentrantLock interruptLock = new ReentrantLock();

    /**
     * Condition that is signaled when the state is changed either by closing or by signaling an interrupt.
     */
    protected final Condition interruptCondition = interruptLock.newCondition();

    /**
     * ISR instance that will signal the interruptCondition.
     */
    protected final InterruptServiceRoutine interruptServiceRoutine = new InterruptServiceRoutine() {

        @Override
        public void onInterrupt(int aInterrupt) {
            interruptLock.lock();
            interruptCondition.signalAll();
            interruptLock.unlock();
        }
    };

    /**
     * Peer ID of the other Peer we are talking to (for the purpose of sending interrupts.
     */
    protected int otherPeer;

    /**
     * size of this ring buffer. determined by the writer. This size is without control bytes.
     */
    protected long size = -1;

    /**
     * flag if this RingBuffer object is reading or writing to the RingBuffer at address.
     */
    protected boolean isReadFlag = false;

    /**
     * Spin time if either this side or the other side does not support interrupt based communication.
     */
    protected long spinTimeWithoutInterrupts;

    /**
     * Spin time that is used when interrupts are used.
     * This is not needed if interrupts are received reliably.
     * If however an interrupt is lost then this is the maximum time we will wait for one before manually checking if the
     * state has changed. If the state has not changed after spinTimeWithoutInterrupts has elapsed then we will simply wait for interrupts again in a loop.
     */
    protected long spinTimeWithInterrupts;

    protected boolean wasOpened = false;

    protected boolean useInterrupts = false;

    protected long timeout = -1;


    /**
     * Constructor for creating a spin based RingBuffer.
     */
    public RingBuffer(IvshmemMemory aSharedMemory, long aAddress, long spinTime) {
        this(aSharedMemory, aAddress, spinTime, spinTime);

    }

    /**
     * Constructor for interrupt based RingBuffer. Be aware that both ends need to support interrupts!
     * The parameter spinTimeWithoutInterrupts only becomes relevant if the other end reports not supporting interrupts during connecting.
     */
    public RingBuffer(IvshmemMemory aSharedMemory, long aAddress, long spinTimeWithoutInterrupts, long spinTimeWithInterrupts) {
        address = aAddress;
        shmemory = aSharedMemory;
        memory = shmemory.getMemory();
        this.spinTimeWithoutInterrupts = spinTimeWithoutInterrupts;
        this.spinTimeWithInterrupts = spinTimeWithInterrupts;

        if (!memory.isValid(aAddress)) {
            throw new IllegalArgumentException("Shared memory is too small to hold the buffer with the given size at the given offset!");
        }
    }

    /**
     * closes this ring buffer.
     * Does not throw an exception.
     */
    public void close() {
        accessLock.lock();
        try {
            if (closedFlag) {
                return;
            }
            closedFlag = true;

            if (!shmemory.isClosed()) {
                if (useInterrupts) {
                    shmemory.removeInterruptServiceRoutine(vector, interruptServiceRoutine);
                }
                try {
                    memory.write(address, STATE_CLOSED);
                    triggerInterrupt();
                } catch (Exception e) {
                    //DC.
                }
            }
            interruptLock.lock();
            interruptCondition.signalAll();
            interruptLock.unlock();
        } finally {
            accessLock.unlock();
        }
    }

    /**
     * Sets the timeout for either read or write operations. Set to -1 to disable timeout.
     * After timout a reading call or writing call will throw an IOException if no bytes can be read.
     * Be aware that after a timout exception occures the stream can be used again normally if you simply wish to
     * try again.
     */
    public void setTimeout(long timeout) {
        accessLock.lock();
        try {
            this.timeout = timeout;
        } finally {
            accessLock.unlock();
        }
    }

    /**
     * Sets the spin time.
     */
    public void setSpinTime(long spinTime) {
        setSpinTime(spinTime, spinTime);
    }


    /**
     * Sets the spin time
     */
    public void setSpinTime(long spinTimeWithoutInterrupts, long spinTimeWithInterrupts) {
        this.spinTimeWithoutInterrupts = spinTimeWithoutInterrupts;
        this.spinTimeWithInterrupts = spinTimeWithInterrupts;
    }

    /**
     * Retuns true if interrupts are used for signaling changes in the ring buffer.
     * <p>
     * throws IllegalStateException on an unconnected ring buffer as interrupts can only be used if
     * both sides support them.
     */
    public boolean usesInterrupts() {
        if (!wasOpened()) {
            throw new IllegalStateException("Not open!");
        }

        return useInterrupts;
    }

    /**
     * Will write the memory area to Zero.
     * Should only be called by the Application that is going to createOrOpen the
     * Output Stream and only when its certain that there is no longer an Active RingBuffer at the address.
     */
    public void cleanMemoryArea() {
        memory.set(address, (byte) 0, OVERHEAD);
    }

    /**
     * Will read the vector from where it is supposed to be stored without performing any check if the ring buffer is connected.
     * This may be usefull to send a interrupt to the other side if it is known to be "stuck".
     * Be aware that this method may return garbage data.
     */
    public int readVector() {
        return memory.readInt(address + VECTOR_OFFSET);
    }

    public int getOtherPeer() {
        if (!usesInterrupts()) {
            throw new IllegalStateException("Doesnt use interrupts.");
        }

        return otherPeer;
    }

    /**
     * Call this method to attempt to close another RingBuffer at this address if you suspect that it may be lingering around after an application crash.
     * This method should be called before any connection attempts were made.
     */
    public void tryCloseExternally(int aPeer, int aVector) {
        memory.write(address, STATE_CLOSED);
        shmemory.sendInterrupt(aPeer, aVector);
    }


    /**
     * returns the amount of actual data bytes that can be in the buffer at any given time.
     * This will differ from the size specified by the constructor of the OutputStream by OVERHEAD as the amount of bytes
     * needed for control data will be subtracted from that amount.
     */
    public long getBufferSize() throws IOException {
        if (size == -1) {
            throw new IllegalStateException("Unconnected");
        }
        return size;
    }


    /**
     * gets the current write index. The write will point to a offset relative from address+BUFFER_START_OFFSET where the next byte will be written to.
     */
    protected long getWriteIndex() {
        if (isReadFlag) {
            return memory.readLong(address + WRITE_INDEX_OFFSET);
        }
        return localIndex;

    }

    /**
     * Changes the write index in the shared memory.
     * Will Close the RingBuffer if the write index that is stored inside the local index field
     * does not match the one in the shared memory.
     * If this call succeeds then the local index field is automatically updated to reflect the new write index.
     */
    protected void setWriteIndex(long aNewIndex) {
        if (isReadFlag) {
            throw new RuntimeException("Cannot call this method while reading!");
        }

        if (!memory.compareAndSet(address + WRITE_INDEX_OFFSET, localIndex, aNewIndex)) {
            close();
            throw new RuntimeException("Write index was modified externally!");
        }

        localIndex = aNewIndex;
    }

    /**
     * gets the current read index. The read index will point to a offset relative from address+BUFFER_START_OFFSET where the next byte will be read from.
     */
    protected long getReadIndex() {
        if (isReadFlag) {
            return localIndex;
        }

        return memory.readLong(address + READ_INDEX_OFFSET);
    }

    protected long getSpinTimeToUse() {
        return useInterrupts ? spinTimeWithInterrupts : spinTimeWithoutInterrupts;
    }

    /**
     * Changes the read index in the shared memory.
     * Will Close the RingBuffer if the read index that is stored inside the local index field
     * does not match the one in the shared memory.
     * If this call succeeds then the local index field is automatically updated to reflect the new read index.
     */
    protected void setReadIndex(long aNewIndex) {
        if (!isReadFlag) {
            throw new RuntimeException("Cannot call this method while writing!");
        }

        if (!memory.compareAndSet(address + READ_INDEX_OFFSET, localIndex, aNewIndex)) {
            close();
            throw new RuntimeException("Read index was modified externally!");
        }

        localIndex = aNewIndex;
    }


    /*
     * Will trigger an interrupt on the other peer. This is used to tell the other peer to recheck its state as
     * new bytes have either been read or can be read or the RingBuffer was closed.
     */
    protected void triggerInterrupt() {
        if (useInterrupts) {
            shmemory.sendInterrupt(otherPeer, vector);
        }
    }

    /**
     * Returns the current state byte.
     */
    protected byte getState() {
        return memory.read(STATE_OFFSET + address);
    }

    protected boolean setState(byte expect, byte newstate) {
        try {
            return memory.compareAndSet(STATE_OFFSET + address, expect, newstate);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Will check if we are still in STATE_CONNECTED.
     */
    protected void checkStateConnected() {
        if (!wasOpened()) {
            throw new RuntimeException("Not open!");
        }
        if (isClosed()) {
            throw new RuntimeException("Closed!");
        }
        if (getState() != STATE_CONNECTED) {
            close();
            throw new RuntimeException("Closed!");
        }
    }

    /**
     * Returns true if we are able to createOrOpen a OutputStream.
     */
    public boolean canConnectOutputStream() {
        if (isClosed()) {
            return false;
        }

        if (wasOpened()) {
            return false;
        }

        if (getState() != STATE_UNCONNECTED) {
            return false;
        }

        return true;
    }

    /**
     * Returns true if we are able to createOrOpen a input stream without blocking.
     */
    public boolean canConnectInputStream() {
        if (closedFlag) {
            return false;
        }
        if (wasOpened()) {
            return false;
        }

        byte tempState = getState();
        if (tempState == STATE_UNCONNECTED || tempState == STATE_CLOSED) {
            return false;
        }

        return true;
    }

    /**
     * returns true if RingBuffer is closed.
     */
    public boolean isClosed() {
        if (!closedFlag && shmemory.isClosed()) {
            close();
        }
        return closedFlag;
    }

    /**
     * returns true if this RingBuffer was connected at some point.
     */
    public boolean wasOpened() {
        return wasOpened;
    }

    /**
     * returns true if this RingBuffer is currently open.
     */
    public boolean isOpen() {
        try {
            return !isClosed() && wasOpened() && getState() == STATE_CONNECTED;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    /**
     * Will attempt to createOrOpen and connect the output stream using spinning.
     * Keep in mind that this output stream can be interrupted during writing by calling Thread.interrupt().
     * If this is done then partial data may be truncated. The amount of bytes truncated can be retrieved from the
     * RingBufferInterruptedException that is thrown when an interrupt occurs.
     *
     * @param aBufferSize the size of the buffer in bytes. (Note: this must include OVERHEAD)
     * @param aTimeout    the connect timeout in which a input stream must be created to connect to this output stream. If it expires a SharedMemoryException will be thrown.
     * @param aSpinTime   during the connection process this thread will spin.
     *                    If this value is write to a low value then the output stream may connect faster but it will also tax the CPU more during the connection process.
     *                    This value should be at least 10 times smaller than your timeout. It is only used during the connecting and has no effect after the connection was established.
     * @return the Output Stream.
     * @throws InterruptedException  If the thread was interrupted while connecting.
     */
    public OutputStream connectOutputStream(long aBufferSize, long aTimeout, long aSpinTime, TimeUnit aUnit) throws InterruptedException {
        accessLock.lock();
        try {
            if (isClosed()) {
                throw new RuntimeException("Already closed!");
            }

            if (wasOpened()) {
                throw new RuntimeException("Was already opened!s");
            }

            vector = -1;
            useInterrupts = false;
            return connectOutputStreamInternal(aBufferSize, aTimeout, aSpinTime, aUnit);
        } finally {
            accessLock.unlock();
        }
    }

    /**
     * Will attempt to createOrOpen and connect the output stream using interrupts (if the other side supports this).
     * Keep in mind that this output stream can be interrupted during writing by calling Thread.interrupt().
     * If this is done then partial data may be truncated. The amount of bytes truncated can be retrieved from the
     * RingBufferInterruptedException that is thrown when an interrupt occurs.
     *
     * @param aVector     the vector to use for communication. This will be transmitted to the InputStream via SharedMemory.
     * @param aBufferSize the size of the buffer in bytes. (Note: this must include OVERHEAD)
     * @param aTimeout    the connect timeout in which a input stream must be created to connect to this output stream. If it expires a SharedMemoryException will be thrown.
     * @param aSpinTime   during the connection process this thread will spin.
     *                    If this value is write to a low value then the output stream may connect faster but it will also tax the CPU more during the connection process.
     *                    This value should be at least 10 times smaller than your timeout. It is only used during the connecting and has no effect after the connection was established.
     * @return the Output Stream.
     * @throws InterruptedException  If the thread was interrupted while connecting.
     */
    public OutputStream connectOutputStream(int aVector, long aBufferSize, long aTimeout, long aSpinTime, TimeUnit aUnit) throws InterruptedException {
        accessLock.lock();
        try {
            if (isClosed()) {
                throw new RuntimeException("Already closed!");
            }

            if (wasOpened()) {
                throw new RuntimeException("Was already opened!s");
            }

            if (!shmemory.supportsInterrupts()) {
                throw new RuntimeException("memory doesnt support interrupts!");
            }

            if (!shmemory.hasOwnPeerID()) {
                throw new RuntimeException("memory doesnt support peers!");
            }

            if (!shmemory.isVectorValid(aVector)) {
                throw new RuntimeException("invalid vector");
            }

            vector = aVector;
            useInterrupts = true;

            return connectOutputStreamInternal(aBufferSize, aTimeout, aSpinTime, aUnit);
        } finally {
            accessLock.unlock();
        }
    }

    /**
     * Internal method to connect the output stream.
     */
    protected OutputStream connectOutputStreamInternal(long aBufferSize, long aTimeout, long aSpinTime, TimeUnit aUnit) throws InterruptedException {
        long tempTimeout = TimeUnit.MILLISECONDS.convert(aTimeout, aUnit);
        long tempSpinTime = TimeUnit.MILLISECONDS.convert(aSpinTime, aUnit);

        if (getState() != STATE_UNCONNECTED) {
            throw new RuntimeException("The memory area already contains data! Set the first " + OVERHEAD + " bytes to 0 if you are sure that it is safe to do so!");
        }

        if (aBufferSize < OVERHEAD + 1) {
            throw new RuntimeException("Buffer must be at least " + (OVERHEAD + 2) + " bytes big to store overhead and at least 2 bytes for transfer!");
        }

        if (!memory.isValid(address, aBufferSize)) {
            throw new RuntimeException("Shared memory is too small to hold the buffer with the given size at the given offset!");
        }

        if (useInterrupts) {
            memory.write(address + WRITE_PEER_OFFSET, shmemory.getOwnPeerID());
            memory.write(address + INTERRUPT_FLAG_WRITE_OFFSET, (byte) 1);
            memory.write(address + VECTOR_OFFSET, vector);

        } else {
            memory.write(address + INTERRUPT_FLAG_WRITE_OFFSET, (byte) 0);
        }

        memory.write(address + SIZE_OFFSET, aBufferSize);
        memory.write(address + WRITE_INDEX_OFFSET, localIndex);


        isReadFlag = false;
        wasOpened = true;
        size = aBufferSize - OVERHEAD;
        if (useInterrupts) {
            shmemory.registerInterruptServiceRoutine(vector, interruptServiceRoutine);
        }
        if (!setState(STATE_UNCONNECTED, STATE_CONNECTING)) {
            close();
            throw new RuntimeException("Setting state to Connecting failed! Was another Output Stream created?");
        }

        if (tempTimeout < 0) {
            tempTimeout = Long.MAX_VALUE;
        }

        long tempStart = System.currentTimeMillis();
        while (true) {

            if (System.currentTimeMillis() - tempStart > tempTimeout) {
                close();
                throw new RuntimeException("Timeout while waiting for input stream to open, the ring buffer is now closed!");
            }

            byte tempState;

            try {
                tempState = getState();
            } catch (Exception exc) {
                close();
                throw exc;
            }

            if (tempState == 0 || closedFlag) {
                close();
                throw new RuntimeException("Ring Buffer closed before it could open!");
            }

            if (tempState == 1) {
                try {
                    Thread.sleep(tempSpinTime);
                } catch (InterruptedException exc) {
                    close();
                    throw exc;
                }
                continue;
            }

            if (tempState == 2) {
                try {
                    if (useInterrupts) {
                        if (memory.read(address + INTERRUPT_FLAG_READ_OFFSET) != 1) {
                            shmemory.removeInterruptServiceRoutine(vector, interruptServiceRoutine);
                            useInterrupts = false;
                        } else {
                            otherPeer = memory.readInt(address + READ_PEER_OFFSET);
                            if (shmemory.knowsOtherPeers() && !shmemory.isOtherPeerConnected(otherPeer)) {
                                throw new RuntimeException("Other peer reported a peer id that is not connected!");
                            }
                        }
                    }

                } catch (Exception exc) {
                    close();
                    throw exc;
                }
                return new RingBufferOutputStream();
            }

            close();
            throw new RuntimeException("External write overwrote state byte with garbage data!");
        }
    }

    /**
     * Try to connect the Input Stream to a Output Stream.
     *
     * @param aTimeout  timeout before throwing a Shared Memory Exception
     * @param aSpinTime during the connection process this thread will spin.
     *                  If this value is write to a low value then the output stream may connect faster but it will also tax the CPU more during the connection process.
     *                  This value should be at least 10 times smaller than your timeout.
     * @return the connected InputStream that can be read from.
     * @throws InterruptedException
     */
    public InputStream connectInputStream(long aTimeout, long aSpinTime, TimeUnit aUnit) throws InterruptedException {
        long tempTimeout = TimeUnit.MILLISECONDS.convert(aTimeout, aUnit);
        long tempSpinTime = TimeUnit.MILLISECONDS.convert(aSpinTime, aUnit);

        accessLock.lock();
        try {
            if (isClosed()) {
                throw new RuntimeException("Already closed!");
            }

            if (wasOpened()) {
                throw new IllegalStateException("Already configured for either output or input!");
            }

            long tempStart = System.currentTimeMillis();
            while (true) {
                byte tempState = getState();

                if (tempState == STATE_CONNECTING) {
                    break;
                }

                if (System.currentTimeMillis() - tempStart > tempTimeout) {
                    throw new RuntimeException("Timeout while waiting for input stream to open, the ring buffer is now closed!");
                }

                Thread.sleep(tempSpinTime);
            }

            long tempSize = memory.readLong(address + SIZE_OFFSET);

            if (tempSize < OVERHEAD + 1) {
                throw new IllegalArgumentException("Buffer must be at least " + (OVERHEAD + 2) + " bytes big to store overhead and at least 2 bytes for transfer!");
            }

            if (!memory.isValid(address, tempSize)) {
                throw new IllegalArgumentException("Shared memory is too small to hold the buffer with the given size at the given offset!");
            }

            if (memory.read(address + INTERRUPT_FLAG_WRITE_OFFSET) == 1) {
                useInterrupts = true;
            } else {
                useInterrupts = false;
            }

            if (!shmemory.supportsInterrupts() || !shmemory.hasOwnPeerID()) {
                useInterrupts = false;
            }

            int tempVector = -1;
            if (useInterrupts) {
                tempVector = readVector();
                if (!shmemory.isVectorValid(tempVector)) {
                    useInterrupts = false;
                }
            }
            int tempPeer = -1;
            if (useInterrupts) {
                tempPeer = memory.readInt(address + WRITE_PEER_OFFSET);
                if (shmemory.knowsOtherPeers()) {
                    if (!shmemory.isOtherPeerConnected(tempPeer)) {
                        useInterrupts = false;
                    }
                }
            }


            wasOpened = true;
            isReadFlag = true;
            size = tempSize - OVERHEAD;

            if (useInterrupts) {
                otherPeer = tempPeer;
                vector = tempVector;
                memory.write(address + READ_PEER_OFFSET, shmemory.getOwnPeerID());
                memory.write(address + INTERRUPT_FLAG_READ_OFFSET, (byte) 1);
                shmemory.registerInterruptServiceRoutine(tempVector, interruptServiceRoutine);
            } else {
                memory.write(address + INTERRUPT_FLAG_READ_OFFSET, (byte) 0);
            }

            if (!setState(STATE_CONNECTING, STATE_CONNECTED)) {
                close();
                throw new RuntimeException("Setting state to Connected failed! Was another Input Stream created or did the Output Stream just timeout?");
            }

            return new RingBufferInputStream();
        } finally {
            accessLock.unlock();
        }
    }

    /**
     * Returns the amount of byte currently reable in a single read.
     * Differs from getAvailableBytes in that is doesnt include readable bytes before the read index
     * as those would require a wrap around in the ring buffer.
     */
    protected long getReadableBytesInSingleRead() throws IOException {
        long tempWriteIndex = getWriteIndex();
        long tempReadIndex = getReadIndex();

        if (tempReadIndex <= tempWriteIndex) {
            return tempWriteIndex - tempReadIndex;
        }
        return size - tempReadIndex;
    }

    /**
     * Returns the amount of bytes currently writeable in a single write.
     * Differs from getFreeBytes in that it doesnt include the amount of writeable bytes before write index
     * as those would require a wrap around in the ring buffer.
     */
    protected long getBytesWritableInSingleWrite() throws IOException {
        long tempWriteIndex = getWriteIndex();
        long tempReadIndex = getReadIndex();

        long tempWB;
        if (tempReadIndex <= tempWriteIndex) {
            tempWB = size - tempWriteIndex;
        } else {
            tempWB = tempReadIndex - tempWriteIndex;
        }

        if ((tempWriteIndex + tempWB) % size == tempReadIndex) {
            tempWB--;
        }

        return tempWB;
    }

    /**
     * Returns the total amount of bytes that can be currently written to the ring buffer before writing calls would
     * start to block due to the buffer being full.
     */
    public long getFreeBytes() throws IOException {
        checkStateConnected();

        long tempWriteIndex = getWriteIndex();
        long tempReadIndex = getReadIndex();

        long tempWB;
        if (tempReadIndex <= tempWriteIndex) {
            tempWB = (size - tempWriteIndex) + tempReadIndex;
        } else {
            tempWB = tempReadIndex - tempWriteIndex;
        }

        if ((tempWriteIndex + tempWB) % size == tempReadIndex) {
            tempWB--;
        }

        return tempWB;
    }

    /**
     * Returns the total amount of bytes that can be read from the ring buffer without blocking.
     */
    public long getAvailableBytes() throws IOException {
        checkStateConnected();
        long tempWriteIndex = getWriteIndex();
        long tempReadIndex = getReadIndex();

        if (tempReadIndex <= tempWriteIndex) {
            return tempWriteIndex - tempReadIndex;
        }
        return (size - tempReadIndex) + tempWriteIndex;
    }

    /**
     * Waits until at least one readable byte is readable from the buffer.
     * Will return the amount of readable bytes which is guaranteed to be at least 1 or greater.
     * <p>
     * Will only return amount of bytes that can be read in a single read operation (i.e. without wraparound in the ring buffer).
     */
    protected long waitForAtLeastOneReadableByte() throws IOException {
        long tempStart = System.currentTimeMillis();

        checkStateConnected();
        long tempSpinTime = getSpinTimeToUse();
        long tempBytes = getReadableBytesInSingleRead();
        while (tempBytes == 0) {
            checkStateConnected();
            if (tempSpinTime <= 0) {
                tempBytes = getReadableBytesInSingleRead();
                continue;
            }

            interruptLock.lock();
            try {
                tempBytes = getReadableBytesInSingleRead();
                if (tempBytes == 0) {
                    try {
                        interruptCondition.await(tempSpinTime, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        throw new RingBufferInterruptedException(e);
                    }
                    checkStateConnected();
                } else {
                    return tempBytes;
                }
            } finally {
                interruptLock.unlock();
            }

            tempBytes = getReadableBytesInSingleRead();

            if (tempBytes == 0 && timeout >= 0 && System.currentTimeMillis() > tempStart + timeout) {
                throw new RingBufferTimeoutException("Read Timeout");
            }
        }

        return tempBytes;
    }

    /**
     * Utility method to wait until a certain number of bytes is available to write without blocking.
     * The singleWrite parameter toggles if the method should only wait for bytes that be written to
     * in a single write operation (i.e. they dont require a wrap around in the ring buffer).
     * <p>
     * Returns the amount of available bytes.
     */
    protected long waitForWritableBytes(int count, boolean singleWrite) throws IOException {
        long tempStart = System.currentTimeMillis();

        checkStateConnected();
        long tempSpinTime = getSpinTimeToUse();
        long tempBytes = singleWrite ? getBytesWritableInSingleWrite() : getFreeBytes();
        while (tempBytes < count) {
            checkStateConnected();
            if (tempSpinTime <= 0) {
                tempBytes = singleWrite ? getBytesWritableInSingleWrite() : getFreeBytes();
                continue;
            }
            interruptLock.lock();
            try {
                tempBytes = singleWrite ? getBytesWritableInSingleWrite() : getFreeBytes();
                if (tempBytes < count) {
                    try {
                        interruptCondition.await(tempSpinTime, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        throw new RingBufferInterruptedException(e);
                    }

                    checkStateConnected();
                } else {
                    return tempBytes;
                }
            } finally {
                interruptLock.unlock();
            }
            tempBytes = singleWrite ? getBytesWritableInSingleWrite() : getFreeBytes();

            if (tempBytes < count && timeout >= 0 && System.currentTimeMillis() > tempStart + timeout) {
                throw new RingBufferTimeoutException("Write Timeout");
            }
        }

        return tempBytes;
    }


    class RingBufferInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            accessLock.lock();
            try {
                waitForAtLeastOneReadableByte();
                long tempIndex = getReadIndex();
                int tempByte = memory.read(address + BUFFER_START_OFFSET + tempIndex) & 0xFF;
                setReadIndex((tempIndex + 1) % size);
                triggerInterrupt();
                return tempByte;
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }

            accessLock.lock();
            try {
                int tempBytes = (int) Math.min(len, waitForAtLeastOneReadableByte());
                long tempIndex = getReadIndex();
                memory.read(address + BUFFER_START_OFFSET + tempIndex, b, off, tempBytes);
                setReadIndex((tempIndex + tempBytes) % size);
                triggerInterrupt();
                return tempBytes;
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public long skip(long n) throws IOException {
            accessLock.lock();
            try {
                long tempSkipped = 0;
                try {
                    while (n > 0) {
                        long tempBytesToSkip = Math.min(n, waitForAtLeastOneReadableByte());
                        n -= tempBytesToSkip;
                        long tempNewReadIndex = getReadIndex() + tempBytesToSkip;
                        setReadIndex(tempNewReadIndex % size);
                        triggerInterrupt();
                    }
                } catch (RingBufferInterruptedException | RingBufferTimeoutException exc) {
                    //DC.
                }
                return tempSkipped;
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public int available() throws IOException {
            accessLock.lock();
            try {
                long tempBytes = getFreeBytes();
                if (tempBytes > Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }

                return (int) tempBytes;
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public void close() {
            RingBuffer.this.close();
        }
    }


    class RingBufferOutputStream extends OutputStream {

        public void write(int b) throws IOException {
            accessLock.lock();
            try {
                waitForWritableBytes(1, true);
                long tempIndex = getWriteIndex();
                memory.write(address + BUFFER_START_OFFSET + tempIndex, (byte) b);
                setWriteIndex((tempIndex + 1) % size);
                triggerInterrupt();
            } finally {
                accessLock.unlock();
            }
        }


        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            accessLock.lock();
            try {

                if (timeout >= 0) {
                    if (len >= size) {
                        throw new IOException("You have write a timeout, to avoid having to do partial writes the entire write operation has to fit into the ring buffer at once. " +
                                "You intend to write " + len + " bytes but the ring buffer is only " + size + " bytes big. (1 byte is reserved) If you wish to enable partial writes dont write a timeout.");
                    }
                    waitForWritableBytes(len, false);
                }

                while (len > 0) {
                    try {
                        long tempCurrentPass = writePartial(b, off, len);
                        off += tempCurrentPass;
                        len -= tempCurrentPass;
                    } catch (RingBufferInterruptedException exc) {
                        exc.bytesTruncated = len;
                        throw exc;
                    }
                }
            } finally {
                accessLock.unlock();
            }
        }

        /**
         * writes the byte array partially returning the amount of bytes written.
         * This is required when we reach the high edge of the buffer and need to wrap around.
         * In this case even if the entire write operation would fit into the buffer we would need
         * to make 2 writes regardless. This method is also useful when only a few bytes can be written
         * because the buffer is full.
         */
        protected int writePartial(byte[] b, int off, int len) throws IOException {
            if (len <= 0) {
                return 0;
            }

            int tempBytes = (int) Math.min(len, waitForWritableBytes(1, true));
            long tempIndex = getWriteIndex();
            memory.write(address + BUFFER_START_OFFSET + tempIndex, b, off, tempBytes);
            setWriteIndex((tempIndex + tempBytes) % size);
            triggerInterrupt();
            return tempBytes;
        }

        @Override
        public void close() {
            RingBuffer.this.close();
        }
    }

    public static class RingBufferTimeoutException extends IOException {
        public RingBufferTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when the reading/writing thread is interrupted during write/read operations.
     */
    public static class RingBufferInterruptedException extends IOException {
        protected int bytesTruncated;

        public RingBufferInterruptedException(InterruptedException exc) {
            super(exc);
        }

        /**
         * If a write operation is interrupted then this method call will return the amount of bytes at the end of the
         * buffer that were truncated (as in not written to the ring buffer) in a partial write operation.
         * If this returns 0 then this call was either a reading call or no bytes were truncated.
         *
         * @return
         */
        public int getBytesTruncated() {
            return bytesTruncated;
        }
    }
}
