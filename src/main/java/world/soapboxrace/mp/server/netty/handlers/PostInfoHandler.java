package world.soapboxrace.mp.server.netty.handlers;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.socket.DatagramPacket;
import world.soapboxrace.mp.race.MpClient;
import world.soapboxrace.mp.race.MpClients;
import world.soapboxrace.mp.race.MpSession;
import world.soapboxrace.mp.race.MpSessions;
import world.soapboxrace.mp.util.ArrayReader;

import java.nio.ByteBuffer;
import java.util.*;

public class PostInfoHandler extends BaseHandler
{
    // Ugh...
    private static Map<Integer, List<Integer>> postInfoMap = new HashMap<>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
    {
        DatagramPacket datagramPacket = (DatagramPacket) msg;
        ByteBuf buf = datagramPacket.content();

        if (isPlayerInfo(buf))
        {
            logger.info("Received post info packet!");
            MpClient mpClient = MpClients.get(datagramPacket);
            if (mpClient != null)
            {
                if (mpClient.isSyncOk())
                {
                    postInfoOk(mpClient, ByteBufUtil.getBytes(buf));
                }
            }
        }
        
        super.channelRead(ctx, msg);
    }

    private boolean isPlayerInfo(ByteBuf buf)
    {
        return buf.getByte(0) == (byte) 0x01;
    }

    private void postInfoOk(MpClient mpClient, byte[] packet)
    {
        MpSession mpSession = MpSessions.get(mpClient);
        if (mpSession.isAllSyncOk())
        {
            Map<Integer, MpClient> mpClients = mpSession.getClients();
            Iterator<Map.Entry<Integer, MpClient>> iterator = mpClients.entrySet().iterator();
            while (iterator.hasNext())
            {
                Map.Entry<Integer, MpClient> next = iterator.next();
                MpClient value = next.getValue();
                if (!next.getKey().equals(mpClient.getPort()))
                {
                    value.send(transformByteTypeB(value, packet, mpClient).array());

                    postInfoMap.computeIfAbsent(value.getPort(), ArrayList::new);
                    postInfoMap.get(value.getPort()).add(mpClient.getPort());

                    if (postInfoMap.get(value.getPort()).size() == mpSession.getMaxUsers() - 1)
                    {
                        value.incrementSequenceB();
                        postInfoMap.get(value.getPort()).clear();
                    }
                }
            }
        }
    }

    private ByteBuffer transformByteTypeB(MpClient clientTo, byte[] packet, MpClient clientFrom)
    {
        byte[] clone = packet.clone();

        try
        {
            clone = fixTime(clientTo, packet);
        } catch (Exception e)
        {
            e.printStackTrace();
        }

        if (clone.length < 4)
        {
            return null;
        }

        byte[] seqArray = clientTo.getSequenceB();
        ByteBuffer buffer = ByteBuffer.allocate(clone.length - 3);

        buffer.put((byte) 0x01);
        buffer.put(clientFrom.getClientId());
        buffer.put(seqArray);

        buffer.putShort((short) 0xffff); // unknown counter
        buffer.putShort((short) 0xffff); // unknown

        for (int i = 10; i < (clone.length - 1); i++)
        {
            buffer.put(clone[i]);
        }

        return buffer;
    }

    private byte[] fixTime(MpClient client, byte[] packet)
    {
        byte[] timeArray = ByteBuffer.allocate(2).putShort((short) (client.getTimeDiff())).array();

        ArrayReader reader = new ArrayReader(packet);

        reader.seek(10);

        while (reader.getPosition() < reader.getLength())
        {
            byte packetId = reader.readByte();

            if (packetId == (byte) 0xff)
            {
                break;
            }

            byte packetLength = reader.readByte();

            if (packetId == 0x12)
            {
                packet[reader.getPosition()] = timeArray[0];
                packet[reader.getPosition() + 1] = timeArray[1];
            }

            reader.seek(packetLength, true);
        }

        return packet;
    }
}
