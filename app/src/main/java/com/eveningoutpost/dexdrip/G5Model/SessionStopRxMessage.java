package com.eveningoutpost.dexdrip.G5Model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// created by jamorham

public class SessionStopRxMessage extends TransmitterMessage {

    public static final byte opcode = 0x29;
    final byte length = 17;
    private byte status = (byte)0xFF;
    private byte received = (byte)0xFF;
    final String transmitterId;
    int sessionStartTime=0;
    int sessionStopTime =0;
    int transitterTime=0;
    boolean valid = false;

    public SessionStopRxMessage(byte[] packet,String transmitterId) {
        this.transmitterId = transmitterId;
        if (packet.length == length) {
            data = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
            if ((data.get() == opcode) && checkCRC(packet)) {
                valid = true;
                status = data.get();
                received = data.get();
                sessionStopTime = data.getInt();
                sessionStartTime = data.getInt();
                transitterTime = data.getInt();

            }
        }
    }

    boolean isValid() {
        return valid;
    }

    boolean isOkay() {
        return isValid() && status == 0x00;
    }

    long getSessionStart() {
        if (isOkay() && sessionStartTime > 0) {
            return DexTimeKeeper.fromDexTime(transmitterId, sessionStartTime);
        } else {
            return 0;
        }
    }
}
