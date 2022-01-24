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

package io.github.alexanderschuetz97.tcp2ivshmem;

import io.github.alexanderschuetz97.tcp2ivshmem.ivshmem.RingBuffer;
import io.github.alexanderschuetz97.ivshmem4j.api.Ivshmem;
import io.github.alexanderschuetz97.ivshmem4j.api.IvshmemMemory;
import io.github.alexanderschuetz97.nativeutils.api.NativeMemory;
import io.github.alexanderschuetz97.nativeutils.impl.NativeLibraryLoaderHelper;
import org.junit.*;

import java.io.*;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RingBufferTest {

    private Random rng = new Random();

    private ExecutorService ex;

    private File shmemfile;

    private NativeMemory memoryInput;

    private NativeMemory memoryOutput;

    private IvshmemMemory shmemoryInput;

    private IvshmemMemory shmemoryOutput;

    private RingBuffer bufferInput;

    private RingBuffer bufferOutput;

    private OutputStream outputStream;

    private InputStream inputStream;

    private DataOutputStream dout;

    private DataInputStream din;

    @BeforeClass
    public static void setupJNI() {
        System.out.println("These Tests might take a lot of time!");
        NativeLibraryLoaderHelper.loadNativeLibraries();
    }

    @Before
    public void before() throws Throwable {
        rng.setSeed(System.currentTimeMillis());
        shmemfile = new File("/dev/shm/" + getClass().getSimpleName() + Math.abs(rng.nextInt()));
        if (shmemfile.exists()) {
            shmemfile.delete();
        }
        shmemoryInput = Ivshmem.plain(shmemfile.getAbsolutePath(), 4096);
        shmemoryOutput = Ivshmem.plain(shmemfile.getAbsolutePath(), 4096);
        memoryInput = shmemoryInput.getMemory();
        memoryOutput = shmemoryOutput.getMemory();

        shmemfile.deleteOnExit();
        rng.setSeed(0);

        for (long i = 0; i < memoryInput.size(); i++) {
            memoryInput.write(i, (byte) 0);
        }

        bufferInput = new RingBuffer(shmemoryInput, 0, 64);
        bufferOutput = new RingBuffer(shmemoryOutput, 0, 64);
        ex = Executors.newCachedThreadPool();
    }

    @After
    public void after() {
        ex.shutdownNow();
        bufferInput.close();
        bufferOutput.close();
        memoryInput.close();
        memoryOutput.close();
        shmemfile.delete();
    }

    @Test
    public void connectBuffers() throws Throwable {
        Assert.assertFalse(bufferInput.wasOpened());
        Assert.assertFalse(bufferOutput.wasOpened());
        Assert.assertFalse(bufferInput.isClosed());
        Assert.assertFalse(bufferOutput.isClosed());
        Assert.assertFalse(bufferInput.isOpen());
        Assert.assertFalse(bufferOutput.isOpen());

        Assert.assertFalse(bufferInput.canConnectInputStream());
        Assert.assertTrue(bufferOutput.canConnectOutputStream());


        Future<OutputStream> tempFutur = ex.submit(new Callable<OutputStream>() {

            @Override
            public OutputStream call() throws Exception {
                return bufferOutput.connectOutputStream(4096, 5000, 64, TimeUnit.MILLISECONDS);
            }
        });

        long tempTime = System.currentTimeMillis();

        while (!bufferInput.canConnectInputStream()) {
            if (System.currentTimeMillis() - tempTime > 5000) {
                //The Future likely failed print its exception if not
                tempFutur.get();
                //Then fail anyways.
                Assert.fail("Took to long!");
            }
        }

        inputStream = bufferInput.connectInputStream(0, 64, TimeUnit.MILLISECONDS);
        outputStream = tempFutur.get(5000, TimeUnit.MILLISECONDS);

        Assert.assertNotNull(inputStream);
        Assert.assertNotNull(outputStream);

        dout = new DataOutputStream(outputStream);
        din = new DataInputStream(inputStream);
    }


    @Test
    public void basic() throws Throwable {
        connectBuffers();

        dout.writeUTF("Hello World");
        Assert.assertEquals("Hello World", din.readUTF());

        int tempInt = rng.nextInt();
        dout.writeInt(tempInt);
        Assert.assertEquals(tempInt, din.readInt());

        int tempShort = rng.nextInt() & 0xffff;
        dout.writeShort(tempShort);
        Assert.assertEquals(tempShort, din.readUnsignedShort());


        int tempByte = rng.nextInt() & 0xff;
        dout.writeByte(tempByte);
        Assert.assertEquals(tempByte, din.readUnsignedByte());

        byte[] tempWrite = new byte[64];
        byte[] tempRead = new byte[64];
        rng.nextBytes(tempWrite);
        dout.write(tempWrite);
        din.readFully(tempRead);
        Assert.assertTrue(Arrays.equals(tempWrite, tempRead));
    }


    private static final int DURATION = 60000;
    private static final int BUFSIZ = 444;

    @Test
    public void testReadWriteALot() throws Throwable {
        connectBuffers();
        //bufferInput.setSpinTime(0);
        //bufferOutput.setSpinTime(0);
        final ConcurrentLinkedQueue<byte[]> tempQ = new ConcurrentLinkedQueue<>();
        final AtomicBoolean go = new AtomicBoolean(true);

        Future<?> tempFutur = ex.submit(new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                while (go.get()) {
                    byte[] tempB = new byte[BUFSIZ];
                    rng.nextBytes(tempB);
                    tempQ.add(tempB);
                    outputStream.write(tempB);
                }

                return null;
            }
        });

        Future<?> tempFutur2 = ex.submit(new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                byte[] tempBuf = new byte[BUFSIZ];
                long tempT = 0;
                long tempB = 0;
                while (go.get()) {
                    while (tempQ.peek() == null && go.get()) {
                        Thread.sleep(1);
                    }

                    if (!go.get()) {
                        return null;
                    }
                    long tempCur = System.nanoTime();
                    din.readFully(tempBuf);
                    tempT += (System.nanoTime() - tempCur);
                    tempB += tempBuf.length;
                    Assert.assertTrue(Arrays.equals(tempBuf, tempQ.poll()));
                    //System.out.println("Performance " + ((long) Math.floor(((double)tempB) / (((double)tempT) / 1000000000d))) + " b/s");
                }
                return null;
            }
        });

        long tempStart = System.currentTimeMillis();
        while (System.currentTimeMillis() - tempStart < DURATION) {
            Thread.sleep(200);
            if (tempFutur.isDone()) {
                tempFutur.get();
                Assert.fail();
            }

            if (tempFutur2.isDone()) {
                tempFutur2.get();
                Assert.fail();
            }
        }
        go.set(false);


        tempFutur.get(5000, TimeUnit.MILLISECONDS);
        tempFutur2.get(5000, TimeUnit.MILLISECONDS);
    }

    @Test
    public void testReadWriteBlock() throws Throwable {
        connectBuffers();

        Assert.assertEquals(bufferOutput.getBufferSize() - 1, bufferOutput.getFreeBytes());
        Assert.assertEquals(0, bufferInput.getAvailableBytes());
        Future tempFutur = ex.submit(new Callable<Integer>() {

            @Override
            public Integer call() throws Exception {
                return din.readInt();
            }
        });

        try {
            tempFutur.get(2000, TimeUnit.MILLISECONDS);
            Assert.fail();
        } catch (TimeoutException exc) {

        }

        dout.writeInt(25);
        Assert.assertEquals(new Integer(25), tempFutur.get());

        Assert.assertEquals(bufferOutput.getBufferSize() - 1, bufferOutput.getFreeBytes());
        int tempSiz = (int) bufferOutput.getFreeBytes();
        byte[] toFill = new byte[tempSiz];
        rng.nextBytes(toFill);
        dout.write(toFill);
        Assert.assertEquals(0, bufferOutput.getFreeBytes());
        Assert.assertEquals(bufferInput.getBufferSize() - 1, bufferInput.getAvailableBytes());

        tempFutur = ex.submit(new Callable<Object>() {

            @Override
            public Object call() throws Exception {
                dout.write(0);
                return null;
            }
        });

        try {
            tempFutur.get(2000, TimeUnit.MILLISECONDS);
            Assert.fail();
        } catch (TimeoutException exc) {

        }

        Assert.assertEquals(0, bufferOutput.getFreeBytes());
        Assert.assertEquals(bufferInput.getBufferSize() - 1, bufferInput.getAvailableBytes());

        din.read();

        tempFutur.get(2000, TimeUnit.MILLISECONDS);

        Assert.assertEquals(0, bufferOutput.getFreeBytes());
        Assert.assertEquals(bufferInput.getBufferSize() - 1, bufferInput.getAvailableBytes());

        din.readFully(new byte[128]);

        Assert.assertEquals(128, bufferOutput.getFreeBytes());
        Assert.assertEquals(bufferInput.getBufferSize() - 129, bufferInput.getAvailableBytes());
    }

    @Test
    public void testReadWriteTimeout() throws Throwable {
        connectBuffers();
        bufferInput.setTimeout(500);
        bufferOutput.setTimeout(500);

        Assert.assertEquals(bufferOutput.getBufferSize() - 1, bufferOutput.getFreeBytes());
        Assert.assertEquals(0, bufferInput.getAvailableBytes());
        Callable<Integer> tempCall = new Callable<Integer>() {

            @Override
            public Integer call() throws Exception {
                return din.readInt();
            }
        };

        Future tempFutur = ex.submit(tempCall);

        try {
            tempFutur.get(2000, TimeUnit.MILLISECONDS);
            Assert.fail();
        } catch (TimeoutException exc) {
            Assert.fail();
        } catch (ExecutionException exc) {
            Assert.assertTrue(exc.getCause() instanceof RingBuffer.RingBufferTimeoutException);
        }


        Assert.assertEquals(bufferOutput.getBufferSize() - 1, bufferOutput.getFreeBytes());
        Assert.assertEquals(0, bufferInput.getAvailableBytes());

        tempFutur = ex.submit(tempCall);
        Thread.sleep(100);
        dout.writeInt(4);

        Assert.assertEquals(new Integer(4), tempFutur.get(2000, TimeUnit.MILLISECONDS));
        Assert.assertEquals(bufferOutput.getBufferSize() - 1, bufferOutput.getFreeBytes());
        Assert.assertEquals(0, bufferInput.getAvailableBytes());

        int tempSiz = (int) bufferOutput.getFreeBytes();
        byte[] toFill = new byte[tempSiz];
        rng.nextBytes(toFill);
        dout.write(toFill);
        Assert.assertEquals(0, bufferOutput.getFreeBytes());
        Assert.assertEquals(bufferInput.getBufferSize() - 1, bufferInput.getAvailableBytes());

        tempCall = new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                dout.writeInt(4);
                return null;
            }
        };

        tempFutur = ex.submit(tempCall);

        try {
            tempFutur.get(2000, TimeUnit.MILLISECONDS);
            Assert.fail();
        } catch (TimeoutException exc) {
            Assert.fail();
        } catch (ExecutionException exc) {
            Assert.assertTrue(exc.getCause() instanceof RingBuffer.RingBufferTimeoutException);
        }


        Assert.assertEquals(0, bufferOutput.getFreeBytes());
        Assert.assertEquals(bufferInput.getBufferSize() - 1, bufferInput.getAvailableBytes());

        tempFutur = ex.submit(tempCall);
        Thread.sleep(100);
        din.readInt();
        tempFutur.get(5000, TimeUnit.MILLISECONDS);

        Assert.assertEquals(0, bufferOutput.getFreeBytes());
        Assert.assertEquals(bufferInput.getBufferSize() - 1, bufferInput.getAvailableBytes());
    }
}
