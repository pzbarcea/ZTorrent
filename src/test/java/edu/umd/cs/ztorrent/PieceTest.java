package edu.umd.cs.ztorrent;

import edu.umd.cs.ztorrent.message.MessageRequest;
import org.junit.Test;

import java.util.List;

public class PieceTest {
    @Test
    public void testPiece() {
        //test the get blocks simple loop should confirm this thing as stable:
        Piece p = new Piece(0, 128 * 1024);
        MessageRequest first = p.getNextBlock();
        boolean b;
        for (int i = 0; i < 8; i++) {
            b = p.addData(first.begin, new byte[first.len]);
            first = p.getNextBlock();
            if (b)
                System.out.println("true on i=" + i);
        }

        //test our get all pieces function.
        p = new Piece(0, 128 * 1024);
        p.addData(64 * 1024, new byte[1024]);
        List<MessageRequest> rList = p.getAllBlocksLeft();
        for (MessageRequest r : rList) {
            System.out.println("Block Size " + r.len);
            p.addData(r.begin, new byte[r.len]);
        }
        //wahoo
        System.out.println("	Test 2: " + p.isComplete());

        p = new Piece(0, 128 * 1024);
        //p.addData(64*1024,new byte[1024]);
        rList = p.getAllBlocksLeft();
        for (MessageRequest r : rList) {
            System.out.println("Block Size " + r.len);
            p.addData(r.begin, new byte[r.len]);
        }

        System.out.println("	Test 3: " + p.isComplete());
    }
}
