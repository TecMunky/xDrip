package com.eveningoutpost.dexdrip.G5Model;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// created by jamorham


public class EGlucoseTxMessage extends TransmitterMessage {

    final byte opcode = 0x4e;

    public EGlucoseTxMessage() {
        data = ByteBuffer.allocate(6);
        data.order(ByteOrder.LITTLE_ENDIAN);
        data.put(opcode);
        appendCRC();
    }

}
