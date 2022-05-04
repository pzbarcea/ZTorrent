package edu.umd.cs.ztorrent.ui;

import edu.umd.cs.ztorrent.Torrent;
import edu.umd.cs.ztorrent.TorrentClient;
import edu.umd.cs.ztorrent.TorrentParser;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;


/***
 * zTorrent UI
 */
public class TorrentUI extends JFrame implements ActionListener {
    //TODO: damn download bar!!

    private static final long serialVersionUID = 1L;
    final JFileChooser fileChooser;
    final JButton start, stop, open, delete;
    final JSplitPane mainPane;
    public final JTable torrentList;
    final TorrentClient client;

    public TorrentUI(TorrentClient client) {
        this.client = client;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());//get look and feel of whatever OS we're using
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException |
                 UnsupportedLookAndFeelException ex) {
        }


        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                client.on = false;
            }
        });

        setSize(400, 300);
        open = new JButton("Open");
        start = new JButton("Start");
        stop = new JButton("Stop");
        delete = new JButton("Delete");

        JPanel topPanel = new JPanel(new GridLayout(1, 5));
        topPanel.add(open);
        topPanel.add(delete);
        topPanel.add(start);
        topPanel.add(stop);

        //Name,size, progress bar, dl down, dl up
        torrentList = new JTable(client);
        JScrollPane scrollPane = new JScrollPane(torrentList);

        torrentList.setFillsViewportHeight(true);
        scrollPane.setBorder(BorderFactory.createLineBorder(Color.BLACK));

        torrentList.setShowGrid(true);
        torrentList.setShowHorizontalLines(true);
        torrentList.setShowVerticalLines(true);
        torrentList.setGridColor(Color.GRAY);
        torrentList.setFillsViewportHeight(true);
        torrentList.setPreferredSize(new Dimension(500, 300));


        JPanel bottom = new JPanel() {
//            @Override
//            public Dimension getPreferredSize() {
//                return new Dimension(200, 200);
//            }
        };
        bottom.setBorder(BorderFactory.createLineBorder(Color.BLACK));

        GridBagLayout experimentLayout = new GridBagLayout();
        this.setLayout(experimentLayout);


        GridBagConstraints c = new GridBagConstraints();
        mainPane = new JSplitPane();
        mainPane.setTopComponent(scrollPane);
        mainPane.setBottomComponent(bottom);
        mainPane.setOrientation(JSplitPane.VERTICAL_SPLIT);

        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.PAGE_START;
//        c.gridheight =1;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 0;
        this.add(topPanel, c);

        c.fill = GridBagConstraints.BOTH;
        c.anchor = GridBagConstraints.CENTER;
//        c.gridheight = GridBagConstraints.RELATIVE;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        c.gridy = 1;
        this.add(mainPane, c);
        open.addActionListener(this);
        stop.addActionListener(this);
        delete.addActionListener(this);
        start.addActionListener(this);

        fileChooser = new JFileChooser();
        mainPane.setDividerLocation(265);
        this.pack();
        setTitle("zTorrent Client");
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    @Override
    public void actionPerformed(ActionEvent e) {

        if (e.getSource() == open) {
            int returnVal = fileChooser.showOpenDialog(TorrentUI.this);

            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = fileChooser.getSelectedFile();
                //This is where a real application would open the file.
                System.out.println("Opening: " + file.getName());
                try {
                    Torrent t = TorrentParser.parseTorrentFile(file.getAbsolutePath());
                    client.addTorrent(t);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(mainPane, "Invalid torrent file");
                }
            } else {
                System.out.println("Open command cancelled by user.");
            }
        } else if (e.getSource() == delete) {
            System.out.println("delete ");
            System.out.println("Delete " + torrentList.getSelectedRow());
            client.deleteTorrentData(client.getTorrent(torrentList.getSelectedRow()));

            //TODO: Choices...
        } else if (e.getSource() == stop) {
            System.out.println("stop ");
            System.out.println("Stop " + torrentList.getSelectedRow());
            try {
                client.setTorrentInactive(client.getTorrent(torrentList.getSelectedRow()));
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        } else if (e.getSource() == start) {
            System.out.println("play ");
            System.out.println("Start " + torrentList.getSelectedRow());
            //Effectively just reload torrent.
            try {
                client.reActivate(client.getTorrent(torrentList.getSelectedRow()));
            } catch (IOException | NoSuchAlgorithmException e1) {
                e1.printStackTrace();
            }


        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        // @TODO: open the client with a specific torrent

        TorrentClient client = new TorrentClient();
        final TorrentUI ex = new TorrentUI(client);
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                ex.setVisible(true);
            }
        });

        client.mainLoop();
    }
}
