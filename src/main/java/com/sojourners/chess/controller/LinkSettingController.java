package com.sojourners.chess.controller;

import com.sojourners.chess.App;
import com.sojourners.chess.config.Properties;
import com.sojourners.chess.util.DialogUtils;
import com.sojourners.chess.util.StringUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;


public class LinkSettingController {

    @FXML
    private TextField linkScanTime;
    @FXML
    private TextField linkThreadNum;

    @FXML
    private TextField mouseClickDelay;

    @FXML
    private TextField mouseMoveDelay;

    @FXML
    private ComboBox<String> linkAiModel;

    /** 下拉框显示名与软件内部标识（与模型文件名对应，见《模型溯源说明》）。
     *  yolo11 为主力；pose 为备援——只在 yolo11 识别失败时上场救急。 */
    private static final String MODEL_YOLO = "yolo11（yolov11.onnx·主力）";
    private static final String MODEL_POSE = "pose（4_v6-0301.onnx + nano_v3-0319.onnx·识别失败时救场）";

    private Properties prop;

    @FXML
    void cancelButtonClick(ActionEvent e) {
        App.closeLinkSetting();
    }

    @FXML
    void okButtonClick(ActionEvent e) {

        String txt = linkScanTime.getText();
        if (!StringUtils.isPositiveInt(txt)) {
            DialogUtils.showErrorDialog("失败", "输入扫描时间错误");
            return;
        }
        prop.setLinkScanTime(Long.parseLong(txt));
        txt = linkThreadNum.getText();
        if (!StringUtils.isPositiveInt(txt)) {
            DialogUtils.showErrorDialog("失败", "输入扫描扫描线程数量错误");
            return;
        }
        prop.setLinkThreadNum(Integer.parseInt(txt));

        txt = mouseClickDelay.getText();
        if (!StringUtils.isNonNegativeInt(txt)) {
            DialogUtils.showErrorDialog("失败", "输入鼠标点击延迟错误");
            return;
        }
        prop.setMouseClickDelay(Integer.parseInt(txt));
        txt = mouseMoveDelay.getText();
        if (!StringUtils.isNonNegativeInt(txt)) {
            DialogUtils.showErrorDialog("失败", "输入鼠标走子延迟错误");
            return;
        }
        prop.setMouseMoveDelay(Integer.parseInt(txt));

        if (linkAiModel.getValue() != null) {
            prop.setLinkAiModel(MODEL_YOLO.equals(linkAiModel.getValue()) ? "yolo11" : "pose");
        }

        App.closeLinkSetting();
    }

    public void initialize() {

        prop = Properties.getInstance();

        linkScanTime.setText(String.valueOf(prop.getLinkScanTime()));
        linkThreadNum.setText(String.valueOf(prop.getLinkThreadNum()));

        mouseClickDelay.setText(String.valueOf(prop.getMouseClickDelay()));
        mouseMoveDelay.setText(String.valueOf(prop.getMouseMoveDelay()));

        linkAiModel.getItems().setAll(MODEL_YOLO, MODEL_POSE);
        linkAiModel.setValue("yolo11".equalsIgnoreCase(prop.getLinkAiModel()) ? MODEL_YOLO : MODEL_POSE);

    }

}
