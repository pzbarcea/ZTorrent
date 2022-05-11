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
    JTable allTorrents;
    TorrentClient client;
    JFileChooser filePicker;
    JButton resumeButton;
    JButton pauseButton;
    JButton addButton;
    JButton deleteButton;
    JPanel mainPane;

    public TorrentUI(TorrentClient client) {
        this.client = client;
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());

        //https://docs.oracle.com/javase/specs/jls/se7/html/jls-14.html#jls-14.20
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException | UnsupportedLookAndFeelException ex) {
            System.err.println("[ERROR] Error starting zTorrent GUI: " + ex);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> client.on = false));

        addButton = new JButton("Add");
        resumeButton = new JButton("Resume");
        pauseButton = new JButton("Pause");
        deleteButton = new JButton("Remove");

        JPanel buttonPanel = new JPanel(new GridLayout(1, 5));
        buttonPanel.add(addButton);
        buttonPanel.add(resumeButton);
        buttonPanel.add(pauseButton);
        buttonPanel.add(deleteButton);

        allTorrents = new JTable(client);
        JScrollPane scrollPane = new JScrollPane(allTorrents);

        allTorrents.setFont(new Font("Serif", Font.PLAIN, 16));
        allTorrents.setFillsViewportHeight(true);
        scrollPane.setBorder(BorderFactory.createLineBorder(Color.BLACK));

        allTorrents.setShowGrid(true);
        allTorrents.setShowHorizontalLines(true);
        allTorrents.setShowVerticalLines(true);
        allTorrents.setGridColor(Color.GRAY);
        allTorrents.setFillsViewportHeight(true);
        allTorrents.setPreferredSize(new Dimension(1000, 400));
        allTorrents.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        allTorrents.getColumnModel().getColumn(0).setPreferredWidth(200);

        buttonPanel.setBackground(Color.darkGray);
        allTorrents.setBackground(Color.darkGray);
        allTorrents.setForeground(Color.WHITE);
        scrollPane.setBackground(Color.darkGray);

        JPanel banner = new JPanel();
        banner.setBackground(Color.BLACK);
        banner.setForeground(Color.lightGray);

        JLabel label = new JLabel();
        label.setText("zTorrent: Paul Zbarcea, Harvey Sun");
        label.setForeground(Color.lightGray);
        label.setFont(new Font("Serif", Font.PLAIN, 40));
        banner.add(label);

        JSplitPane splitPane = new JSplitPane();
        splitPane.setTopComponent(scrollPane);
        splitPane.setBottomComponent(banner);
        splitPane.setOrientation(0);
        splitPane.setDividerLocation(350);

        GridBagLayout experimentLayout = new GridBagLayout();
        this.setLayout(experimentLayout);

        GridBagConstraints c = new GridBagConstraints();

        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.PAGE_START;
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.weighty = 0;
        this.add(buttonPanel, c);

        c.fill = GridBagConstraints.BOTH;
        c.anchor = GridBagConstraints.CENTER;
        c.weightx = 1;
        c.weighty = 1;
        c.gridx = 0;
        c.gridy = 1;
        this.add(splitPane, c);

        addButton.addActionListener(this);
        pauseButton.addActionListener(this);
        deleteButton.addActionListener(this);
        resumeButton.addActionListener(this);

        filePicker = new JFileChooser();
        this.pack();
        setTitle("zTorrent Client");
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    @Override
    public void actionPerformed(ActionEvent e) {

        if (e.getSource() == addButton) {
            int returnVal = filePicker.showOpenDialog(TorrentUI.this);

            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = filePicker.getSelectedFile();
                //This is where a real application would open the file.
                System.out.println("[STATUS] Opening Torrent File: " + file.getName());
                try {
                    Torrent t = TorrentParser.parseTorrentFile(file.getAbsolutePath());
                    client.addTorrent(t);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(mainPane, "Could not open torrent file");
                    ex.printStackTrace();
                }
            } else {
                System.out.println("[ERROR] Aborted torrent open");
            }
        } else if (e.getSource() == deleteButton) {
            System.out.println("[STATUS] Deleted " + allTorrents.getSelectedRow());
            client.deleteTorrentData(client.getTorrent(allTorrents.getSelectedRow()));

        } else if (e.getSource() == pauseButton) {
            System.out.println("[STATUS] Stopped " + allTorrents.getSelectedRow());
            try {
                client.setTorrentInactive(client.getTorrent(allTorrents.getSelectedRow()));
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        } else if (e.getSource() == resumeButton) {
            System.out.println("[STATUS] Resumed " + allTorrents.getSelectedRow());
            try {
                client.reActivate(client.getTorrent(allTorrents.getSelectedRow()));
            } catch (IOException | NoSuchAlgorithmException e1) {
                e1.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        TorrentClient client = new TorrentClient();
        TorrentUI ex = new TorrentUI(client);
        ex.setSize(1000, 485);
        SwingUtilities.invokeLater(() -> ex.setVisible(true));

        client.mainLoop();
    }
}