package dev.slimevr.gui.dialogs;

import dev.slimevr.autobone.AutoBone;
import dev.slimevr.gui.views.SkeletonConfigView;
import dev.slimevr.poserecorder.PoseFrameIO;
import dev.slimevr.poserecorder.PoseFrames;
import dev.slimevr.poserecorder.PoseRecorder;
import io.eiren.util.StringUtils;
import io.eiren.util.collections.FastList;
import io.eiren.util.logging.LogManager;
import io.eiren.vr.VRServer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.AnchorPane;
import javafx.stage.Stage;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Future;

public class AutoConfigurationDialog  extends AnchorPane implements Initializable {

	private static File saveDir = new File("Recordings");
	private static File loadDir = new File("LoadRecordings");

	private VRServer server;
	private PoseRecorder poseRecorder;
	private AutoBone autoBone;
	private AutoConfigurationListener autoConfigurationListener;

	private transient Thread recordingThread = null;
	private transient Thread saveRecordingThread = null;
	private transient Thread autoBoneThread = null;

	@FXML
	public Label processLabel;

	@FXML
	public Label lengthsLabel;
	@FXML
	public Button startRecordingButton;

	@FXML
	public Button saveRecordingButton;

	@FXML
	public Button adjustButton;

	@FXML
	public Button applyButton;

	public AutoConfigurationDialog() {
	}

	public AutoConfigurationDialog(VRServer server, Stage stage) {
		this.server = server;
		//this.skeletonConfig = skeletonConfig;
		this.poseRecorder = new PoseRecorder(server);
		this.autoBone = new AutoBone(server);
	}

	public void init(VRServer server, Stage stage, AutoConfigurationListener autoConfigurationListener)
	{
		this.server = server;
		//this.skeletonConfig = skeletonConfig;
		this.poseRecorder = new PoseRecorder(server);
		this.autoBone = new AutoBone(server);
		this.autoConfigurationListener = autoConfigurationListener;
		initUi();
	}

	private void initUi() {
		saveRecordingButton.setDisable(!poseRecorder.hasRecording());
		adjustButton.setDisable(!(poseRecorder.hasRecording() || (loadDir.isDirectory() && loadDir.list().length > 0)));
		applyButton.setDisable(true);
	}


	@FXML
	public void startRecordingButtonClicked(ActionEvent actionEvent) {

		if(startRecordingButton.isDisabled() || recordingThread != null) {
			return;
		}

		Thread thread = new Thread() {
			@Override
			public void run() {
				try {
					if(poseRecorder.isReadyToRecord()) {
						setTextJavaFxThread(startRecordingButton,"Recording...");
						// 1000 samples at 20 ms per sample is 20 seconds
						int sampleCount = server.config.getInt("autobone.sampleCount", 1000);
						long sampleRate = server.config.getLong("autobone.sampleRateMs", 20L);
						Future<PoseFrames> framesFuture = poseRecorder.startFrameRecording(sampleCount, sampleRate);
						PoseFrames frames = framesFuture.get();
						LogManager.log.info("[AutoBone] Done recording!");

						disableViewJavaFxThread(saveRecordingButton,false);
						disableViewJavaFxThread(adjustButton,false);

						if(server.config.getBoolean("autobone.saveRecordings", false)) {
							setTextJavaFxThread(startRecordingButton,"Saving...");
							saveRecording(frames);
						}
					} else {
						setTextJavaFxThread(applyButton,"Not Ready...");
						LogManager.log.severe("[AutoBone] Unable to record...");
						Thread.sleep(3000); // Wait for 3 seconds
						return;
					}
				} catch(Exception e) {
					setTextJavaFxThread(applyButton,"Recording Failed...");
					LogManager.log.severe("[AutoBone] Failed recording!", e);
					try {
						Thread.sleep(3000); // Wait for 3 seconds
					} catch(Exception e1) {
						// Ignore
					}
				} finally {
					setTextJavaFxThread(startRecordingButton,"Start Recording");
					recordingThread = null;
				}
			}

		};
		recordingThread = thread;
		thread.start();
	}

	@FXML
	public void saveRecordingButtonClicked(ActionEvent actionEvent) {

		if(saveRecordingButton.isDisabled() || saveRecordingThread != null) {
			return;
		}
		Thread thread = new Thread() {
			@Override
			public void run() {
				try {
					Future<PoseFrames> framesFuture = poseRecorder.getFramesAsync();
					if(framesFuture != null) {
						setTextJavaFxThread(saveRecordingButton,"Waiting for Recording...");
						PoseFrames frames = framesFuture.get();

						if(frames.getTrackerCount() <= 0) {
							throw new IllegalStateException("Recording has no trackers");
						}

						if(frames.getMaxFrameCount() <= 0) {
							throw new IllegalStateException("Recording has no frames");
						}

						setTextJavaFxThread(saveRecordingButton,"Saving...");
						saveRecording(frames);

						setTextJavaFxThread(saveRecordingButton,"Recording Saved!");
						try {
							Thread.sleep(3000); // Wait for 3 seconds
						} catch(Exception e1) {
							// Ignore
						}
					} else {
						setTextJavaFxThread(saveRecordingButton,"No Recording...");
						LogManager.log.severe("[AutoBone] Unable to save, no recording was done...");
						try {
							Thread.sleep(3000); // Wait for 3 seconds
						} catch(Exception e1) {
							// Ignore
						}
						return;
					}
				} catch(Exception e) {
					setTextJavaFxThread(saveRecordingButton,"Saving Failed...");
					LogManager.log.severe("[AutoBone] Failed to save recording!", e);
					try {
						Thread.sleep(3000); // Wait for 3 seconds
					} catch(Exception e1) {
						// Ignore
					}
				} finally {
					setTextJavaFxThread(saveRecordingButton,"Save Recording");
					saveRecordingThread = null;
				}
			}
		};

		saveRecordingThread = thread;
		thread.start();

	}

	@FXML
	public void autoAdjustButtonClicked(ActionEvent actionEvent) {

		if(adjustButton.isDisabled() || autoBoneThread != null) {
			return;
		}

		Thread thread = new Thread() {
			@Override
			public void run() {
				try {
					setTextJavaFxThread(adjustButton,"Load...");
					List<Pair<String, PoseFrames>> frameRecordings = loadRecordings();

					if(!frameRecordings.isEmpty()) {
						LogManager.log.info("[AutoBone] Done loading frames!");
					} else {
						Future<PoseFrames> framesFuture = poseRecorder.getFramesAsync();
						if(framesFuture != null) {
							setTextJavaFxThread(adjustButton,"Waiting for Recording...");
							PoseFrames frames = framesFuture.get();

							if(frames.getTrackerCount() <= 0) {
								throw new IllegalStateException("Recording has no trackers");
							}

							if(frames.getMaxFrameCount() <= 0) {
								throw new IllegalStateException("Recording has no frames");
							}

							frameRecordings.add(Pair.of("<Recording>", frames));
						} else {
							setTextJavaFxThread(adjustButton,"No Recordings...");
							LogManager.log.severe("[AutoBone] No recordings found in \"" + loadDir.getPath() + "\" and no recording was done...");
							try {
								Thread.sleep(3000); // Wait for 3 seconds
							} catch(Exception e1) {
								// Ignore
							}
							return;
						}
					}

					setTextJavaFxThread(adjustButton,"No Recordings...");
					LogManager.log.info("[AutoBone] Processing frames...");
					FastList<Float> heightPercentError = new FastList<Float>(frameRecordings.size());
					for(Pair<String, PoseFrames> recording : frameRecordings) {
						LogManager.log.info("[AutoBone] Processing frames from \"" + recording.getKey() + "\"...");

						heightPercentError.add(processFrames(recording.getValue()));
						LogManager.log.info("[AutoBone] Done processing!");
						disableViewJavaFxThread(applyButton,false);
						//#region Stats/Values
						Float neckLength = autoBone.getConfig("Neck");
						Float chestDistance = autoBone.getConfig("Chest");
						Float torsoLength = autoBone.getConfig("Torso");
						Float hipWidth = autoBone.getConfig("Hips width");
						Float legsLength = autoBone.getConfig("Legs length");
						Float kneeHeight = autoBone.getConfig("Knee height");

						float neckTorso = neckLength != null && torsoLength != null ? neckLength / torsoLength : 0f;
						float chestTorso = chestDistance != null && torsoLength != null ? chestDistance / torsoLength : 0f;
						float torsoWaist = hipWidth != null && torsoLength != null ? hipWidth / torsoLength : 0f;
						float legTorso = legsLength != null && torsoLength != null ? legsLength / torsoLength : 0f;
						float legBody = legsLength != null && torsoLength != null && neckLength != null ? legsLength / (torsoLength + neckLength) : 0f;
						float kneeLeg = kneeHeight != null && legsLength != null ? kneeHeight / legsLength : 0f;

						LogManager.log.info("[AutoBone] Ratios: [{Neck-Torso: " + StringUtils.prettyNumber(neckTorso) + "}, {Chest-Torso: " + StringUtils.prettyNumber(chestTorso) + "}, {Torso-Waist: " + StringUtils.prettyNumber(torsoWaist) + "}, {Leg-Torso: " + StringUtils.prettyNumber(legTorso) + "}, {Leg-Body: " + StringUtils.prettyNumber(legBody) + "}, {Knee-Leg: " + StringUtils.prettyNumber(kneeLeg) + "}]");

						String lengthsString = getLengthsString();
						LogManager.log.info("[AutoBone] Length values: " + lengthsString);
						setTextJavaFxThread(lengthsLabel,lengthsString);
					}

					if(!heightPercentError.isEmpty()) {
						float mean = 0f;
						for(float val : heightPercentError) {
							mean += val;
						}
						mean /= heightPercentError.size();

						float std = 0f;
						for(float val : heightPercentError) {
							float stdVal = val - mean;
							std += stdVal * stdVal;
						}
						std = (float) Math.sqrt(std / heightPercentError.size());

						LogManager.log.info("[AutoBone] Average height error: " + StringUtils.prettyNumber(mean, 6) + " (SD " + StringUtils.prettyNumber(std, 6) + ")");
					}
					//#endregion
				} catch(Exception e) {
					setTextJavaFxThread(adjustButton,"Failed...");
					LogManager.log.severe("[AutoBone] Failed adjustment!", e);
					try {
						Thread.sleep(3000); // Wait for 3 seconds
					} catch(Exception e1) {
						// Ignore
					}
				} finally {
					setTextJavaFxThread(adjustButton,"Auto-Adjust");
					autoBoneThread = null;
				}
			}
		};

		autoBoneThread = thread;
		thread.start();

	}

	@FXML
	public void applyValuesButtonClicked(ActionEvent actionEvent) {
		if(applyButton.isDisabled())
		{
			return;
		}
		autoBone.applyConfig();
		autoConfigurationListener.onConfigurationChanged();
	}

	@Override
	public void initialize(URL location, ResourceBundle resources) {

	}

	private <U extends Labeled> void  setTextJavaFxThread(U labeled, String text)
	{
		Platform.runLater(() -> {
			labeled.setText(text);
		});
	}

	private <U extends Node> void  disableViewJavaFxThread(U node, Boolean disabled)
	{
		Platform.runLater(() -> {
			node.setDisable(disabled);
		});
	}

	private String getLengthsString() {
		boolean first = true;
		StringBuilder configInfo = new StringBuilder("");
		for(Map.Entry<String, Float> entry : autoBone.configs.entrySet()) {
			if(!first) {
				configInfo.append(", ");
			} else {
				first = false;
			}

			configInfo.append(entry.getKey() + ": " + StringUtils.prettyNumber(entry.getValue() * 100f, 2));
		}

		return configInfo.toString();
	}

	private void saveRecording(PoseFrames frames) {
		if(saveDir.isDirectory() || saveDir.mkdirs()) {
			File saveRecording;
			int recordingIndex = 1;
			do {
				saveRecording = new File(saveDir, "ABRecording" + recordingIndex++ + ".pfr");
			} while(saveRecording.exists());

			LogManager.log.info("[AutoBone] Exporting frames to \"" + saveRecording.getPath() + "\"...");
			if(PoseFrameIO.writeToFile(saveRecording, frames)) {
				LogManager.log.info("[AutoBone] Done exporting! Recording can be found at \"" + saveRecording.getPath() + "\".");
			} else {
				LogManager.log.severe("[AutoBone] Failed to export the recording to \"" + saveRecording.getPath() + "\".");
			}
		} else {
			LogManager.log.severe("[AutoBone] Failed to create the recording directory \"" + saveDir.getPath() + "\".");
		}
	}

	private List<Pair<String, PoseFrames>> loadRecordings() {
		List<Pair<String, PoseFrames>> recordings = new FastList<Pair<String, PoseFrames>>();
		if(loadDir.isDirectory()) {
			File[] files = loadDir.listFiles();
			if(files != null) {
				for(File file : files) {
					if(file.isFile() && org.apache.commons.lang3.StringUtils.endsWithIgnoreCase(file.getName(), ".pfr")) {
						LogManager.log.info("[AutoBone] Detected recording at \"" + file.getPath() + "\", loading frames...");
						PoseFrames frames = PoseFrameIO.readFromFile(file);

						if(frames == null) {
							LogManager.log.severe("Reading frames from \"" + file.getPath() + "\" failed...");
						} else {
							recordings.add(Pair.of(file.getName(), frames));
						}
					}
				}
			}
		}

		return recordings;
	}

	private float processFrames(PoseFrames frames) {
		autoBone.minDataDistance = server.config.getInt("autobone.minimumDataDistance", autoBone.minDataDistance);
		autoBone.maxDataDistance = server.config.getInt("autobone.maximumDataDistance", autoBone.maxDataDistance);

		autoBone.numEpochs = server.config.getInt("autobone.epochCount", autoBone.numEpochs);

		autoBone.initialAdjustRate = server.config.getFloat("autobone.adjustRate", autoBone.initialAdjustRate);
		autoBone.adjustRateDecay = server.config.getFloat("autobone.adjustRateDecay", autoBone.adjustRateDecay);

		autoBone.slideErrorFactor = server.config.getFloat("autobone.slideErrorFactor", autoBone.slideErrorFactor);
		autoBone.offsetErrorFactor = server.config.getFloat("autobone.offsetErrorFactor", autoBone.offsetErrorFactor);
		autoBone.proportionErrorFactor = server.config.getFloat("autobone.proportionErrorFactor", autoBone.proportionErrorFactor);
		autoBone.heightErrorFactor = server.config.getFloat("autobone.heightErrorFactor", autoBone.heightErrorFactor);
		autoBone.positionErrorFactor = server.config.getFloat("autobone.positionErrorFactor", autoBone.positionErrorFactor);
		autoBone.positionOffsetErrorFactor = server.config.getFloat("autobone.positionOffsetErrorFactor", autoBone.positionOffsetErrorFactor);

		boolean calcInitError = server.config.getBoolean("autobone.calculateInitialError", true);
		float targetHeight = server.config.getFloat("autobone.manualTargetHeight", -1f);
		return autoBone.processFrames(frames, calcInitError, targetHeight, (epoch) -> {
			setTextJavaFxThread(processLabel,epoch.toString());
			setTextJavaFxThread(lengthsLabel,getLengthsString());
		});
	}


	public interface AutoConfigurationListener
	{
		void onConfigurationChanged();
	}


}
