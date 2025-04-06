package com.example.visualassistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class ObjectDetectionOverlay extends View {

    private List<DetectionResult> detectionResults = new ArrayList<>();
    private Paint boxPaint;
    private Paint textPaint;
    private Paint textBackgroundPaint;

    public static class DetectionResult {
        public final RectF boundingBox;
        public final String label;
        public final float confidence;

        public DetectionResult(RectF boundingBox, String label, float confidence) {
            this.boundingBox = boundingBox;
            this.label = label;
            this.confidence = confidence;
        }
    }

    public ObjectDetectionOverlay(Context context) {
        super(context);
        init();
    }

    public ObjectDetectionOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ObjectDetectionOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        boxPaint = new Paint();
        boxPaint.setColor(Color.RED);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(5.0f);

        textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(36.0f);
        textPaint.setAntiAlias(true);

        textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(Color.parseColor("#99000000")); // Semi-transparent black
        textBackgroundPaint.setStyle(Paint.Style.FILL);
    }

    public void setDetectionResults(List<DetectionResult> results) {
        this.detectionResults = results;
        invalidate(); // Trigger redraw
    }

    public void clearDetections() {
        detectionResults.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (DetectionResult result : detectionResults) {
            // Scale bounding box coordinates to match the view dimensions
            float left = result.boundingBox.left * getWidth();
            float top = result.boundingBox.top * getHeight();
            float right = result.boundingBox.right * getWidth();
            float bottom = result.boundingBox.bottom * getHeight();

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint);

            // Format the text to display
            String displayText = result.label + " " + Math.round(result.confidence * 100) + "%";

            // Measure text dimensions
            float textWidth = textPaint.measureText(displayText);
            float textHeight = textPaint.getTextSize();

            // Draw text background
            canvas.drawRect(left, top - textHeight, left + textWidth, top, textBackgroundPaint);

            // Draw text
            canvas.drawText(displayText, left, top - 5, textPaint);
        }
    }
}