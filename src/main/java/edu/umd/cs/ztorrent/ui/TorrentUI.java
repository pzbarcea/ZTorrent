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
 * zTorrent GUI
 */
public class TorrentUI extends JFrame implements ActionListener {
    JTable torrentList;
    TorrentClient client;
    JFileChooser filePicker;
    JButton resumeButton;
    JButton pauseButton;
    JButton openButton;
    JButton deleteButton;
    JSplitPane mainPane;

    public TorrentUI(TorrentClient client) {
        this.client = client;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        //https://docs.oracle.com/javase/specs/jls/se7/html/jls-14.html#jls-14.20
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException ex) {
            System.err.println("Error starting zTorrent GUI: " + ex);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> client.on = false));

        setSize(600, 450);
        openButton = new JButton("Open");
        resumeButton = new JButton("Resume");
        pauseButton = new JButton("Pause");
        deleteButton = new JButton("Remove");

        JPanel topPanel = new JPanel(new GridLayout(1, 5));
        topPanel.add(openButton);
        topPanel.add(resumeButton);
        topPanel.add(pauseButton);
        topPanel.add(deleteButton);

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

        JPanel bottom = new JPanel();
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
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 0;
        this.add(topPanel, c);

        c.fill = GridBagConstraints.BOTH;
        c.anchor = GridBagConstraints.CENTER;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        c.gridy = 1;
        this.add(mainPane, c);
        openButton.addActionListener(this);
        pauseButton.addActionListener(this);
        deleteButton.addActionListener(this);
        resumeButton.addActionListener(this);

        filePicker = new JFileChooser();
        mainPane.setDividerLocation(265); //TODO: CHANGE
        this.pack();
        setTitle("zTorrent Client");
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    @Override
    public void actionPerformed(ActionEvent e) {

        if (e.getSource() == openButton) {
            int returnVal = filePicker.showOpenDialog(TorrentUI.this);

            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = filePicker.getSelectedFile();
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
        } else if (e.getSource() == deleteButton) {
            System.out.println("delete ");
            System.out.println("Delete " + torrentList.getSelectedRow());
            client.deleteTorrentData(client.getTorrent(torrentList.getSelectedRow()));

            //TODO: Choices...
        } else if (e.getSource() == pauseButton) {
            System.out.println("stop ");
            System.out.println("Stop " + torrentList.getSelectedRow());
            try {
                client.setTorrentInactive(client.getTorrent(torrentList.getSelectedRow()));
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        } else if (e.getSource() == resumeButton) {
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
        SwingUtilities.invokeLater(() -> ex.setVisible(true));

        client.mainLoop();
    }
}
