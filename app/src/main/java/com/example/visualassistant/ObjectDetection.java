package com.example.visualassistant;

import java.io.Serializable;

/**
 * Class to represent an object detection result.
 */
public class ObjectDetection implements Serializable {

    private static final long serialVersionUID = 1L;

    private String label;
    private float confidence;
    private float distanceEstimate;
    private float left;
    private float top;
    private float right;
    private float bottom;

    /**
     * Constructor for ObjectDetection.
     *
     * @param label            Detected object label
     * @param confidence       Detection confidence (0.0 - 1.0)
     * @param distanceEstimate Estimated distance to object
     * @param left             Left coordinate of bounding box
     * @param top              Top coordinate of bounding box
     * @param right            Right coordinate of bounding box
     * @param bottom           Bottom coordinate of bounding box
     */
    public ObjectDetection(String label, float confidence, float distanceEstimate,
                           float left, float top, float right, float bottom) {
        this.label = label;
        this.confidence = confidence;
        this.distanceEstimate = distanceEstimate;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    /**
     * @return The label of the detected object.
     */
    public String getLabel() {
        return label;
    }

    /**
     * @return The confidence level of the detection.
     */
    public float getConfidence() {
        return confidence;
    }

    /**
     * @return The estimated distance to the object.
     */
    public float getDistanceEstimate() {
        return distanceEstimate;
    }

    /**
     * @return The left coordinate of the bounding box.
     */
    public float getLeft() {
        return left;
    }

    /**
     * @return The top coordinate of the bounding box.
     */
    public float getTop() {
        return top;
    }

    /**
     * @return The right coordinate of the bounding box.
     */
    public float getRight() {
        return right;
    }

    /**
     * @return The bottom coordinate of the bounding box.
     */
    public float getBottom() {
        return bottom;
    }

    /**
     * @return The width of the bounding box.
     */
    public float getBoxWidth() {
        return right - left;
    }

    /**
     * @return The height of the bounding box.
     */
    public float getBoxHeight() {
        return bottom - top;
    }

    /**
     * Returns a string representation of the object detection.
     *
     * @return A string with label, confidence, and estimated distance.
     */
    @Override
    public String toString() {
        return "ObjectDetection{" +
                "label='" + label + '\'' +
                ", confidence=" + confidence +
                ", distanceEstimate=" + distanceEstimate +
                ", box=(" + left + ", " + top + ", " + right + ", " + bottom + ")" +
                '}';
    }
}
