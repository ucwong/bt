/*
 * Copyright (c) 2016—2017 Andrei Tomashpolskiy and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package bt.net.pipeline;

import bt.net.DataReceiver;
import bt.net.Peer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.function.Function;

public class SocketChannelHandler implements ChannelHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocketChannelHandler.class);

    private final Peer peer;
    private final SocketChannel channel;
    private final ByteBuffer inboundBuffer;
    private final ByteBuffer outboundBuffer;
    private final ChannelHandlerContext context;
    private final DataReceiver dataReceiver;

    public SocketChannelHandler(
            Peer peer,
            SocketChannel channel,
            ByteBuffer inboundBuffer,
            ByteBuffer outboundBuffer,
            Function<ChannelHandler, ChannelHandlerContext> contextFactory,
            DataReceiver dataReceiver) {

        this.peer = peer;
        this.channel = channel;
        this.inboundBuffer = inboundBuffer;
        this.outboundBuffer = outboundBuffer;
        this.context = contextFactory.apply(this);
        this.dataReceiver = dataReceiver;
    }

    @Override
    public Peer peer() {
        return peer;
    }

    @Override
    public void register() {
        dataReceiver.registerChannel(channel, this);
        context.fireChannelRegistered();
    }

    @Override
    public void unregister() {
        dataReceiver.unregisterChannel(channel);
        context.fireChannelUnregistered();
    }

    @Override
    public void activate() {
        dataReceiver.activateChannel(channel);
        context.fireChannelActive();
    }

    @Override
    public void deactivate() {
        dataReceiver.deactivateChannel(channel);
        context.fireChannelInactive();
    }

    @Override
    public void fireChannelReady() {
        try {
            int readLast, readTotal = 0;
            boolean processed = false;
            while ((readLast = channel.read(inboundBuffer)) > 0) {
                processed = false;
                readTotal += readLast;
                if (!inboundBuffer.hasRemaining()) {
                    context.fireDataReceived();
                    processed = true;
                    if (!inboundBuffer.hasRemaining()) {
                        throw new IllegalStateException("Insufficient space in buffer");
                    }
                }
            }
            if (readTotal > 0 && !processed) {
                context.fireDataReceived();
            }
            if (readLast == -1) {
                closeChannel();
                throw new EOFException();
            }

        } catch (IOException e) {
            closeChannel();
            throw new RuntimeException("Unexpected I/O error", e);
        }
    }

    @Override
    public void tryFlush() {
        while (outboundBuffer.hasRemaining()) {
            int written;
            try {
                written = channel.write(outboundBuffer);
            } catch (IOException e) {
                closeChannel();
                throw new RuntimeException("Unexpected I/O error", e);
            }
            if (written == 0) {
                break;
            }
        }
    }

    private void closeChannel() {
        try {
            channel.close();
        } catch (IOException e) {
            LOGGER.error("Failed to close channel for peer: " + peer, e);
        } finally {
            unregister();
        }
    }
}