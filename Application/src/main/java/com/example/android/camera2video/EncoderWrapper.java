/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.camera2video;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Encodes video by streaming to disk.
 */
class EncoderWrapper {

    private static final String TAG = "EncoderWrapper";
    private static final boolean VERBOSE = false;
    private static final int IFRAME_INTERVAL = 1; // sync one frame every second
    private final EncoderThread mEncoderThread;
    private final Surface mInputSurface;
    private final MediaCodec mEncoder;


    public final static int MSG_FRAME_AVAILABLE = 0;
    public final static int MSG_SHUTDOWN = 1;

    public EncoderWrapper(int width,
                     int height,
                     int bitRate,
                     int frameRate,
                     int orientationHint,
                     String mimeType,
                     int codecProfile,
                     File outputFile) throws IOException {
        mEncoder = MediaCodec.createEncoderByType(mimeType);
        mEncoderThread = new EncoderThread(mEncoder, outputFile, orientationHint);
        mInputSurface = mEncoder.createInputSurface();

        //Configures encoder
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (codecProfile != -1) {
            format.setInteger(MediaFormat.KEY_PROFILE, codecProfile);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, getTransferFunction(codecProfile));
        }

        if (VERBOSE) Log.d(TAG, "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

    }

    private int getTransferFunction(int codecProfile) {
        switch (codecProfile) {
            case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10:
                return MediaFormat.COLOR_TRANSFER_HLG;
            case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10:
            case MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus:
                return MediaFormat.COLOR_TRANSFER_ST2084;
            default:
                return MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
        }
    }

    /**
     * Returns the encoder's input surface.
     */
    public Surface getInputSurface() {
        return mInputSurface;
    }

    public void start() {
        mEncoder.start();

        // Start the encoder thread last.  That way we're sure it can see all of the state
        // we've initialized.
        mEncoderThread.start();
        mEncoderThread.waitUntilReady();
    }

    /**
     * Shuts down the encoder thread, and releases encoder resources.
     * <p>
     * Does not return until the encoder thread has stopped.
     */
    public void shutdown() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects");

        Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(MSG_SHUTDOWN));
        try {
            mEncoderThread.join();
        } catch (InterruptedException ie) {
            Log.w(TAG, "Encoder thread join() was interrupted", ie);
        }

        mEncoder.stop();
        mEncoder.release();
    }

    /**
     * Notifies the encoder thread that a new frame is available to the encoder.
     */
    public void frameAvailable() {
        Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(MSG_FRAME_AVAILABLE));
    }

    public void waitForFirstFrame() {
        mEncoderThread.waitForFirstFrame();
    }

    /**
     * Object that encapsulates the encoder thread.
     * <p>
     * We want to sleep until there's work to do.  We don't actually know when a new frame
     * arrives at the encoder, because the other thread is sending frames directly to the
     * input surface.  We will see data appear at the decoder output, so we can either use
     * an infinite timeout on dequeueOutputBuffer() or wait() on an object and require the
     * calling app wake us.  It's very useful to have all of the buffer management local to
     * this thread -- avoids synchronization -- so we want to do the file mux ing in here.
     * So, it's best to sleep on an object and do something appropriate when awakened.
     * <p>
     * This class does not manage the MediaCodec encoder startup/shutdown.  The encoder
     * should be fully started before the thread is created, and not shut down until this
     * thread has been joined.
     */
    static class EncoderThread extends Thread {
        MediaCodec mEncoder;
        MediaFormat mEncodedFormat = null;
        MediaCodec.BufferInfo mBufferInfo;
        MediaMuxer mMuxer;
        int mOrientationHint;
        int mVideoTrack = -1;

        EncoderHandler mHandler;
        int mFrameNum= 0;

        final Object mLock = new Object();

        volatile Boolean mReady = false;

        public EncoderThread(MediaCodec mediaCodec,
                             File outputFile,
                             int orientationHint) throws IOException {
            mEncoder = mediaCodec;
            mBufferInfo = new MediaCodec.BufferInfo();
            mMuxer = new MediaMuxer(outputFile.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mOrientationHint = orientationHint;
        }

        /**
         * Thread entry point.
         * <p>
         * Prepares the Looper, Handler, and signals anybody watching that we're ready to go.
         */
        @Override
        public void run() {
            Looper.prepare();
            mHandler = new EncoderHandler(this);    // must create on encoder thread
            Log.d(TAG, "encoder thread ready");
            synchronized (mLock) {
                mReady = true;
                mLock.notify();    // signal waitUntilReady()
            }

            Looper.loop();

            synchronized (mLock) {
                mReady = false;
                mHandler = null;
            }
            Log.d(TAG, "looper quit");
        }

        /**
         * Waits until the encoder thread is ready to receive messages.
         * <p>
         * Call from non-encoder thread.
         */
        public void waitUntilReady() {
            synchronized (mLock) {
                while (!mReady) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) { /* not expected */ }
                }
            }
        }

        /**
         * Waits until the encoder has processed a single frame.
         * <p>
         * Call from non-encoder thread.
         */
        public void waitForFirstFrame() {
            synchronized (mLock) {
                while (mFrameNum < 1) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ie) {
                        ie.printStackTrace();
                    }
                }
            }
            Log.d(TAG, "Waited for first frame");
        }

        /**
         * Returns the Handler used to send messages to the encoder thread.
         */
        public EncoderHandler getHandler() {
            synchronized (mLock) {
                // Confirm ready state.
                if (!mReady) {
                    throw new RuntimeException("not ready");
                }
            }
            return mHandler;
        }

        /**
         * Drains all pending output from the encoder, and adds it to the circular buffer.
         */
        public Boolean drainEncoder(){
            long TIMEOUT_US = 0L;     // no timeout -- check for buffers, bail if none
            boolean encodedFrame = false;

            while (true) {
                int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_US);
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // no output available yet
                    break;
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Should happen before receiving buffers, and should only happen once.
                    // The MediaFormat contains the csd-0 and csd-1 keys, which we'll need
                    // for MediaMuxer.  It's unclear what else MediaMuxer might want, so
                    // rather than extract the codec-specific data and reconstruct a new
                    // MediaFormat later, we just grab it here and keep it around.
                    mEncodedFormat = mEncoder.getOutputFormat();
                    Log.d(TAG, "encoder output format changed: " + mEncodedFormat);
                } else if (encoderStatus < 0) {
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                            encoderStatus);
                    // let's ignore it
                } else {
                    ByteBuffer encodedData = mEncoder.getOutputBuffer(encoderStatus);
                    if (encodedData == null) {
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");
                    }

                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // The codec config data was pulled out when we got the
                        // INFO_OUTPUT_FORMAT_CHANGED status.  The MediaMuxer won't accept
                        // a single big blob -- it wants separate csd-0/csd-1 chunks --
                        // so simply saving this off won't work.
                        if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                        mBufferInfo.size = 0;
                    }

                    if (mBufferInfo.size != 0) {
                        // adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                        if (mVideoTrack == -1) {
                            mVideoTrack = mMuxer.addTrack(mEncodedFormat);
                            mMuxer.setOrientationHint(mOrientationHint);
                            mMuxer.start();
                            Log.d(TAG, "Started media muxer");
                        }

                        // mEncBuffer.add(encodedData, mBufferInfo.flags,
                        //         mBufferInfo.presentationTimeUs)
                        mMuxer.writeSampleData(mVideoTrack, encodedData, mBufferInfo);
                        encodedFrame = true;

                        if (VERBOSE) {
                            Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer, ts=" +
                                    mBufferInfo.presentationTimeUs);
                        }
                    }

                    mEncoder.releaseOutputBuffer(encoderStatus, false);

                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                        break;      // out of while
                    }
                }
            }

            return encodedFrame;
        }

        /**
         * Drains the encoder output.
         * <p>
         * See notes for {@link EncoderWrapper#frameAvailable()}.
         */
        void frameAvailable() {
            if (VERBOSE) Log.d(TAG, "frameAvailable");
            if (drainEncoder()) {
                synchronized (mLock) {
                    mFrameNum++;
                    mLock.notify();
                }
            }
        }

        /**
         * Tells the Looper to quit.
         */
        void shutdown() {
            if (VERBOSE) Log.d(TAG, "shutdown");
            Looper.myLooper().quit();
            mMuxer.stop();
            mMuxer.release();
        }

        /**
         * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
         * is driving the encoder) to the encoder thread.
         * <p>
         * The object is created on the encoder thread.
         */
        static public class EncoderHandler extends Handler {
            private final WeakReference<EncoderThread> mWeakEncoderThread;

            public EncoderHandler(EncoderThread et) {
                // This shouldn't need to be a weak ref, since we'll go away when the Looper quits,
                // but no real harm in it.
                mWeakEncoderThread = new WeakReference<>(et);
            }

            // runs on encoder thread
            @Override
            public void handleMessage(Message msg) {
                int what = msg.what;
                if (VERBOSE) {
                    Log.v(TAG, "EncoderHandler: what=" + what);
                }

                EncoderThread encoderThread = mWeakEncoderThread.get();
                if (encoderThread == null) {
                    Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null");
                    return;
                }

                switch (what) {
                    case MSG_FRAME_AVAILABLE:
                        encoderThread.frameAvailable();
                        return;
                    case MSG_SHUTDOWN:
                        encoderThread.shutdown();
                        return;
                    default:
                        throw new RuntimeException("unknown message " + what);
                }
            }

        }
    }
}
