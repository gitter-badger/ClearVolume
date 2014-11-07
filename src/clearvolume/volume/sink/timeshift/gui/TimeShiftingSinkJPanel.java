package clearvolume.volume.sink.timeshift.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Image;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.border.EmptyBorder;

import net.miginfocom.swing.MigLayout;
import clearvolume.volume.sink.timeshift.TimeShiftingSink;

public class TimeShiftingSinkJPanel extends JPanel
{

	private JSlider mTimeShiftSlider;
	private JProgressBar mPlayBar;

	public static final void createJFrame(TimeShiftingSink pTimeShiftingSink)
	{
		EventQueue.invokeLater(new Runnable()
		{
			@Override
			public void run()
			{
				try
				{
					JFrame lJFrame = new JFrame();

					TimeShiftingSinkJPanel lMultiChannelTimeShiftingSinkPanel = new TimeShiftingSinkJPanel(pTimeShiftingSink);
					lJFrame.getContentPane()
									.add(lMultiChannelTimeShiftingSinkPanel);
					lJFrame.setVisible(true);
				}
				catch (Exception e)
				{
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the panel.
	 */
	public TimeShiftingSinkJPanel(final TimeShiftingSink pTimeShiftingSink)
	{
		setBackground(Color.WHITE);
		setLayout(new MigLayout("",
														"[grow,fill][grow,fill][grow,fill][grow,fill]",
														"[][26px:26px,center][grow]"));

		JPanel lPastPresentPanel = new JPanel();
		lPastPresentPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
		lPastPresentPanel.setBackground(Color.WHITE);
		add(lPastPresentPanel, "cell 0 0 4 1,grow");
		lPastPresentPanel.setLayout(new BorderLayout(0, 0));

		JLabel lPastLabel = new JLabel("  past");
		lPastPresentPanel.add(lPastLabel, BorderLayout.WEST);

		JLabel lPresentLabel = new JLabel("present     ");
		lPastPresentPanel.add(lPresentLabel, BorderLayout.EAST);

		JLayeredPane lJLayeredPane = new JLayeredPane();
		lJLayeredPane.setBorder(new EmptyBorder(0, 0, 0, 0));
		add(lJLayeredPane, "cell 0 1 4 1,grow");
		lJLayeredPane.setLayout(null);

		mPlayBar = new JProgressBar();
		mPlayBar.setBorder(new EmptyBorder(0, 0, 0, 0));
		mPlayBar.setMinimum(0);
		mPlayBar.setMaximum(Integer.MAX_VALUE);
		setPlayBarPlaying();

		mPlayBar.setBounds(6, 2, 605, 24);
		// lPlayBar.setBackground(new Color(0, 0, 0, 0));
		lJLayeredPane.add(mPlayBar);

		mTimeShiftSlider = new JSlider();
		mTimeShiftSlider.setMaximum(Integer.MAX_VALUE);
		mTimeShiftSlider.setValue(Integer.MAX_VALUE);
		mTimeShiftSlider.setBorder(new EmptyBorder(0, 0, 0, 0));
		lJLayeredPane.setLayer(mTimeShiftSlider, 1);
		mTimeShiftSlider.setPaintTrack(false);
		mTimeShiftSlider.setBounds(6, 0, 616, 29);
		if (pTimeShiftingSink != null)
			mTimeShiftSlider.addChangeListener(e -> {
				final double lNormalizedTimeShift = (Integer.MAX_VALUE - 1.0 * mTimeShiftSlider.getValue()) / Integer.MAX_VALUE;
				pTimeShiftingSink.setTimeShiftNormalized(lNormalizedTimeShift);
			});
		lJLayeredPane.add(mTimeShiftSlider);

		ImageIcon lBeginningIcon = createScaledImageIcon("./icons/beginning.png");
		ImageIcon lPauseIcon = createScaledImageIcon("./icons/pause.png");
		ImageIcon lPlayIcon = createScaledImageIcon("./icons/play.png");
		ImageIcon lEndIcon = createScaledImageIcon("./icons/end.png");

		JButton lGoToBeginButton = new JButton(lBeginningIcon);
		lGoToBeginButton.setBorder(new EmptyBorder(0, 0, 0, 0));
		if (pTimeShiftingSink != null)
			lGoToBeginButton.addActionListener(e -> {
				setTimeShiftSliderToPast();
				pTimeShiftingSink.setTimeShiftNormalized(1);
			});
		add(lGoToBeginButton, "cell 0 2");

		JButton lPauseButton = new JButton(lPauseIcon);
		lPauseButton.setBorder(new EmptyBorder(0, 0, 0, 0));
		if (pTimeShiftingSink != null)
			lPauseButton.addActionListener(e -> {
				setPlayBarPaused();
				pTimeShiftingSink.pause();
			});
		add(lPauseButton, "cell 1 2");

		JButton lPlayButton = new JButton(lPlayIcon);
		lPlayButton.setBorder(new EmptyBorder(0, 0, 0, 0));
		if (pTimeShiftingSink != null)
			lPlayButton.addActionListener(e -> {
				setPlayBarPlaying();
				pTimeShiftingSink.play();
			});
		add(lPlayButton, "cell 2 2");

		JButton lGoToEndButton = new JButton(lEndIcon);
		lGoToEndButton.setBorder(new EmptyBorder(0, 0, 0, 0));
		if (pTimeShiftingSink != null)
			lGoToEndButton.addActionListener(e -> {
				setTimeShiftSliderToNow();
				pTimeShiftingSink.setTimeShiftNormalized(0);
			});
		add(lGoToEndButton, "cell 3 2");

	}

	private void setTimeShiftSliderToNow()
	{
		mTimeShiftSlider.setValue(Integer.MAX_VALUE);
	}

	private void setTimeShiftSliderToPast()
	{
		mTimeShiftSlider.setValue(0);
	}

	private void setPlayBarPaused()
	{
		mPlayBar.setValue(Integer.MAX_VALUE);
	}

	private void setPlayBarPlaying()
	{
		mPlayBar.setValue(Integer.MAX_VALUE - 1);
	}

	protected ImageIcon createScaledImageIcon(String path)
	{
		final int lDownScaling = 8;
		ImageIcon lCreatedImageIcon = createImageIcon(path);
		Image lImage = lCreatedImageIcon.getImage()
																		.getScaledInstance(	lCreatedImageIcon.getIconWidth() / lDownScaling,
																												lCreatedImageIcon.getIconHeight() / lDownScaling,
																												java.awt.Image.SCALE_SMOOTH);

		ImageIcon lImageIcon = new ImageIcon(lImage);
		return lImageIcon;
	}

	protected ImageIcon createImageIcon(String path)
	{
		java.net.URL imgURL = this.getClass().getResource(path);
		if (imgURL != null)
		{
			return new ImageIcon(imgURL, path);
		}
		else
		{
			System.err.println("Couldn't find file: " + path);
			return null;
		}
	}
}