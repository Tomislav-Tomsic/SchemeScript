/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.schemeway.plugins.schemescript.interpreter;

import java.io.*;
import java.net.*;
import java.util.*;

import org.eclipse.core.runtime.*;
import org.eclipse.debug.core.*;
import org.eclipse.debug.core.model.*;

/**
 * Monitors the output stream of a system process and notifies 
 * listeners of additions to the stream.
 * 
 * The output stream monitor reads system out (or err) via
 * and input stream.
 */
public class OutputStreamMonitor implements IFlushableStreamMonitor {
    
    private SocketExceptionHandler fExceptionListener;
    
    /**
     * The stream being monitored (connected system out or err).
     */
    private InputStream fStream;

    /**
     * A collection of listeners
     */
    private List fListeners= new ArrayList(1);
    
    /**
     * Whether content is being buffered
     */
    private boolean fBuffered = true;

    /**
     * The local copy of the stream contents
     */
    private StringBuffer fContents;

    /**
     * The thread which reads from the stream
     */
    private Thread fThread;

    /**
     * The size of the read buffer
     */
    private static final int BUFFER_SIZE= 8192;

    /**
     * Whether or not this monitor has been killed.
     * When the monitor is killed, it stops reading
     * from the stream immediately.
     */
    private boolean fKilled= false;
    
    private long lastSleep;
    
    /**
     * Creates an output stream monitor on the
     * given stream (connected to system out or err).
     */
    public OutputStreamMonitor(InputStream stream, SocketExceptionHandler listener) {
        fStream = new BufferedInputStream(stream, 8192);
        fContents= new StringBuffer();
        fExceptionListener = listener;
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IStreamMonitor#addListener(org.eclipse.debug.core.IStreamListener)
     */
    public synchronized void addListener(IStreamListener listener) {
        fListeners.add(listener);
    }

    /**
     * Causes the monitor to close all
     * communications between it and the
     * underlying stream by waiting for the thread to terminate.
     */
    protected void close() {
        if (fThread != null) {
            Thread thread= fThread;
            fThread= null;
            try {
                thread.join();
            } catch (InterruptedException ie) {
            }
            fListeners.clear();
        }
    }

    /**
     * Notifies the listeners that text has
     * been appended to the stream.
     */
    private void fireStreamAppended(String text) {
        getNotifier().notifyAppend(text);
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IStreamMonitor#getContents()
     */
    public synchronized String getContents() {
        return fContents.toString();
    }

    /**
     * Continually reads from the stream.
     * <p>
     * This method, along with the <code>startReading</code>
     * method is used to allow <code>OutputStreamMonitor</code>
     * to implement <code>Runnable</code> without publicly
     * exposing a <code>run</code> method.
     */
    private void read() {
        lastSleep = System.currentTimeMillis();
        long currentTime = lastSleep;
        byte[] bytes= new byte[BUFFER_SIZE];
        int read = 0;
        while (read >= 0) {
            try {
                if (fKilled) {
                    break;
                }
                read= fStream.read(bytes);
                if (read < 0) {
                    notifiedSocketException(null);
                    return;
                }
                if (read > 0) {
                    String text= new String(bytes, 0, read);
                    synchronized (this) {
                        if (isBuffered()) {
                            fContents.append(text);
                        }
                        fireStreamAppended(text);
                    }
                }
            } catch (SocketException se) {
                notifiedSocketException(se);
                return;
            } catch (IOException ioe) {
                notifiedSocketException(null);
                return;
            } catch (NullPointerException e) {
                // killing the stream monitor while reading can cause an NPE
                // when reading from the stream
                if (!fKilled && fThread != null) {
                    DebugPlugin.log(e);
                }
                return;
            }
            
            currentTime = System.currentTimeMillis();
            if (currentTime - lastSleep > 1000) {
                lastSleep = currentTime;
                try {
                    Thread.sleep(1); // just give up CPU to maintain UI responsiveness.
                } catch (InterruptedException e) {
                } 
            }
        }
        try {
            fStream.close();
        } catch (IOException e) {
            DebugPlugin.log(e);
        }
    }
    
    private void notifiedSocketException(SocketException exception) {
        if (fExceptionListener != null) {
            if (isBuffered()) {
                fContents.append("Connection closed!\n");
            }
            fireStreamAppended("Connection closed!\n");
            fExceptionListener.exceptionOccurred(exception);
        }
    }
    
    protected void kill() {
        fKilled= true;
    }

    /* (non-Javadoc)
     * @see org.eclipse.debug.core.model.IStreamMonitor#removeListener(org.eclipse.debug.core.IStreamListener)
     */
    public synchronized void removeListener(IStreamListener listener) {
        fListeners.remove(listener);
    }

    /**
     * Starts a thread which reads from the stream
     */
    public void startMonitoring() {
        if (fThread == null) {
            fThread= new Thread(new Runnable() {
                public void run() {
                    read();
                }
            }, "OutputStreamMonitor"); //$NON-NLS-1$
            fThread.setDaemon(true);
            fThread.setPriority(Thread.MIN_PRIORITY);
            fThread.start();
        }
    }
    
    /**
     * @see org.eclipse.debug.core.model.IFlushableStreamMonitor#setBuffered(boolean)
     */
    public synchronized void setBuffered(boolean buffer) {
        fBuffered = buffer;
    }

    /**
     * @see org.eclipse.debug.core.model.IFlushableStreamMonitor#flushContents()
     */
    public synchronized void flushContents() {
        fContents.setLength(0);
    }
    
    /**
     * @see IFlushableStreamMonitor#isBuffered()
     */
    public synchronized boolean isBuffered() {
        return fBuffered;
    }

    private ContentNotifier getNotifier() {
        return new ContentNotifier();
    }
    
    class ContentNotifier implements ISafeRunnable {
        
        private IStreamListener fListener;
        private String fText;
        
        /**
         * @see org.eclipse.core.runtime.ISafeRunnable#handleException(java.lang.Throwable)
         */
        public void handleException(Throwable exception) {
            DebugPlugin.log(exception);
        }

        /**
         * @see org.eclipse.core.runtime.ISafeRunnable#run()
         */
        public void run() throws Exception {
            fListener.streamAppended(fText, OutputStreamMonitor.this);
        }

        public void notifyAppend(String text) {
            if (text == null)
                return;
            fText = text;
            Object[] copiedListeners= fListeners.toArray();
            for (int i= 0; i < copiedListeners.length; i++) {
                fListener = (IStreamListener) copiedListeners[i];
                SafeRunner.run(this);
            }
            fListener = null;
            fText = null;       
        }
    }
}
