package plugins.adufour.activecontours;

import icy.image.IcyBufferedImage;
import icy.main.Icy;
import icy.math.ArrayMath;
import icy.painter.Overlay;
import icy.painter.Overlay.OverlayPriority;
import icy.roi.BooleanMask2D;
import icy.roi.ROI;
import icy.roi.ROI2D;
import icy.roi.ROI3D;
import icy.roi.ROIUtil;
import icy.sequence.DimensionId;
import icy.sequence.Sequence;
import icy.sequence.SequenceUtil;
import icy.swimmingPool.SwimmingObject;
import icy.system.IcyHandledException;
import icy.system.SystemUtil;
import icy.system.thread.Processor;
import icy.system.thread.ThreadUtil;
import icy.type.DataType;
import icy.util.ShapeUtil.BooleanOperator;
import icy.util.StringUtil;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.vecmath.Point3d;

import plugins.adufour.activecontours.SlidingWindow.Operation;
import plugins.adufour.blocks.lang.Block;
import plugins.adufour.blocks.util.VarList;
import plugins.adufour.connectedcomponents.ConnectedComponent;
import plugins.adufour.ezplug.EzButton;
import plugins.adufour.ezplug.EzException;
import plugins.adufour.ezplug.EzGroup;
import plugins.adufour.ezplug.EzPlug;
import plugins.adufour.ezplug.EzStoppable;
import plugins.adufour.ezplug.EzVar;
import plugins.adufour.ezplug.EzVarBoolean;
import plugins.adufour.ezplug.EzVarDimensionPicker;
import plugins.adufour.ezplug.EzVarDouble;
import plugins.adufour.ezplug.EzVarEnum;
import plugins.adufour.ezplug.EzVarInteger;
import plugins.adufour.ezplug.EzVarListener;
import plugins.adufour.ezplug.EzVarSequence;
import plugins.adufour.filtering.Convolution1D;
import plugins.adufour.filtering.ConvolutionException;
import plugins.adufour.filtering.Kernels1D;
import plugins.adufour.hierarchicalkmeans.HierarchicalKMeans;
import plugins.adufour.vars.lang.VarBoolean;
import plugins.adufour.vars.lang.VarROIArray;
import plugins.adufour.vars.util.VarException;
import plugins.fab.trackmanager.TrackGroup;
import plugins.fab.trackmanager.TrackManager;
import plugins.fab.trackmanager.TrackSegment;
import plugins.kernel.roi.roi2d.ROI2DArea;
import plugins.kernel.roi.roi2d.ROI2DRectangle;
import plugins.kernel.roi.roi3d.ROI3DArea;
import plugins.kernel.roi.roi3d.ROI3DStack;
import plugins.nchenouard.spot.Detection;

public class ActiveContours extends EzPlug implements EzStoppable, Block
{
    private final double              EPSILON               = 0.0000001;
    
    private final EzVarBoolean        showAdvancedOptions   = new EzVarBoolean("Show advanced options", false);
    
    public final EzVarSequence        input                 = new EzVarSequence("Input");
    private Sequence                  inputData;
    
    public final EzVarDouble          regul_weight          = new EzVarDouble("Contour smoothness", 0.05, 0, 1.0, 0.01);
    
    public final EzGroup              edge                  = new EzGroup("Find bright/dark edges");
    public final EzVarDimensionPicker edge_c                = new EzVarDimensionPicker("Find edges in channel", DimensionId.C, input);
    public final EzVarDouble          edge_weight           = new EzVarDouble("Edge weight", 0, -1, 1, 0.1);
    
    public final EzGroup              region                = new EzGroup("Find homogeneous intensity areas");
    public final EzVarDimensionPicker region_c              = new EzVarDimensionPicker("Find regions in channel", DimensionId.C, input);
    public final EzVarDouble          region_weight         = new EzVarDouble("Region weight", 1.0, 0.0, 1.0, 0.1);
    public final EzVarDouble          region_sensitivity    = new EzVarDouble("Region sensitivity", 1.0, 0.2, 5.0, 0.1);
    
    public final EzVarDouble          balloon_weight        = new EzVarDouble("Contour inflation", 0, -0.5, 0.5, 0.001);
    
    public final EzVarDouble          axis_weight           = new EzVarDouble("Axis constraint", 0, 0.0, 1, 0.1);
    
    public final EzVarBoolean         coupling_flag         = new EzVarBoolean("Multi-contour coupling", true);
    
    public final EzGroup              evolution             = new EzGroup("Evolution parameters");
    public final EzVarSequence        evolution_bounds      = new EzVarSequence("Bound field to ROI of");
    public final EzVarDouble          contour_resolution    = new EzVarDouble("Contour resolution", 2, 0.1, 1000.0, 0.1);
    public final EzVarDouble          contour_timeStep      = new EzVarDouble("Evolution time step", 0.1, 0.1, 10, 0.01);
    public final EzVarInteger         convergence_winSize   = new EzVarInteger("Convergence window size", 50, 10, 10000, 10);
    public final EzVarEnum<Operation> convergence_operation = new EzVarEnum<SlidingWindow.Operation>("Convergence operation", Operation.values(), Operation.VAR_COEFF);
    public final EzVarDouble          convergence_criterion = new EzVarDouble("Convergence criterion", 0.001, 0, 0.1, 0.0001);
    public final EzVarInteger         convergence_nbIter    = new EzVarInteger("Max. iterations", 100000, 100, 100000, 1000);
    
    public enum ExportROI
    {
        NO, ON_INPUT, ON_NEW_IMAGE
    }
    
    public enum ROIType
    {
        AREA, POLYGON,
    }
    
    public final EzVarEnum<ExportROI>           output_rois         = new EzVarEnum<ExportROI>("Export ROI", ExportROI.values(), ExportROI.NO);
    public final EzVarEnum<ROIType>             output_roiType      = new EzVarEnum<ROIType>("Type of ROI", ROIType.values(), ROIType.AREA);
    
    public final EzVarBoolean                   tracking            = new EzVarBoolean("Track objects over time", false);
    
    public final EzVarBoolean                   tracking_newObjects = new EzVarBoolean("Watch entering objects", false);
    
    private final HashMap<TrackSegment, Double> volumes             = new HashMap<TrackSegment, Double>();
    public final EzVarBoolean                   volume_constraint   = new EzVarBoolean("Volume constraint", false);
    public final EzButton                       showTrackManager    = new EzButton("Send to track manager", new ActionListener()
                                                                    {
                                                                        @Override
                                                                        public void actionPerformed(ActionEvent e)
                                                                        {
                                                                            ThreadUtil.invokeLater(new Runnable()
                                                                            {
                                                                                public void run()
                                                                                {
                                                                                    if (trackGroup == null) return;
                                                                                    if (trackGroup.getTrackSegmentList().isEmpty()) return;
                                                                                    
                                                                                    Icy.getMainInterface().getSwimmingPool().add(new SwimmingObject(trackGroup));
                                                                                    TrackManager tm = new TrackManager();
                                                                                    tm.reOrganize();
                                                                                    tm.setDisplaySequence(inputData);
                                                                                }
                                                                            });
                                                                        }
                                                                    });
    
    private Sequence                            edgeData            = new Sequence("Edge information");
    private Sequence                            contourMask_buffer  = new Sequence("Mask data");
    
    private Sequence                            region_data;
    private HashMap<TrackSegment, Double>       region_cin          = new HashMap<TrackSegment, Double>(0);
    private HashMap<TrackSegment, Double>       region_cout         = new HashMap<TrackSegment, Double>(0);
    
    public final VarROIArray                    roiInput            = new VarROIArray("input ROI");
    public final VarROIArray                    roiOutput           = new VarROIArray("Regions of interest");
    
    private boolean                             globalStop;
    
    private TrackGroup                          trackGroup;
    
    private ActiveContoursOverlay               overlay;
    
    private Processor                           multiThreadService  = new Processor(SystemUtil.getAvailableProcessors());
    
    public ActiveContours()
    {
        multiThreadService.setDefaultThreadName("Active Contours");
    }
    
    public TrackGroup getTrackGroup()
    {
        return trackGroup;
    }
    
    @Override
    public void initialize()
    {
        addEzComponent(showAdvancedOptions);
        
        addEzComponent(input);
        
        // regul
        regul_weight.setToolTipText("Higher values result in a smoother contour, but may also slow its growth");
        addEzComponent(regul_weight);
        
        // edge
        edge.setToolTipText("Sets the contour(s) to follow image intensity gradients");
        edge_weight.setToolTipText("Negative (resp. positive) weight pushes contours toward decreasing (resp. increasing) intensities");
        edge.addEzComponent(edge_c, edge_weight);
        addEzComponent(edge);
        
        // region
        region.setToolTipText("Sets the contour(s) to isolate homogeneous intensity regions");
        region_weight.setToolTipText("Set to 0 to deactivate this parameter");
        region_sensitivity.setToolTipText("Increase this value to be more sensitive to dim objects (default: 1)");
        showAdvancedOptions.addVisibilityTriggerTo(region_sensitivity, true);
        region.addEzComponent(region_c, region_weight, region_sensitivity);
        addEzComponent(region);
        
        // balloon force
        balloon_weight.setToolTipText("Positive (resp. negative) values will inflate (resp. deflate) the contour");
        addEzComponent(balloon_weight);
        
        // axis contraint
        axis_weight.setToolTipText("Higher values restrict the evolution along the principal axis");
        addEzComponent(axis_weight);
        
        // coupling
        coupling_flag.setToolTipText("Prevents multiple contours from overlapping");
        showAdvancedOptions.addVisibilityTriggerTo(coupling_flag, true);
        addEzComponent(coupling_flag);
        
        // contour
        contour_resolution.setToolTipText("Sets the contour(s) precision as the distance (in pixels) between control points");
        
        contour_timeStep.setToolTipText("Defines the evolution speed (warning: keep a low value to avoid vibration effects)");
        
        convergence_winSize.setToolTipText("Defines over how many iterations the algorithm should check for convergence");
        showAdvancedOptions.addVisibilityTriggerTo(convergence_winSize, true);
        
        convergence_operation.setToolTipText("Defines the operation used to detect convergence");
        showAdvancedOptions.addVisibilityTriggerTo(convergence_operation, true);
        
        convergence_criterion.setToolTipText("Defines the value of the criterion used to detect convergence");
        
        convergence_nbIter.setToolTipText("Defines the absolute number of iterations to use in case the contour does not converge automatically");
        showAdvancedOptions.addVisibilityTriggerTo(convergence_nbIter, true);
        
        evolution_bounds.setNoSequenceSelection();
        evolution_bounds.setToolTipText("Bounds the evolution of the contour to all ROI of the given sequence (select \"No sequence\" to deactivate)");
        showAdvancedOptions.addVisibilityTriggerTo(evolution_bounds, true);
        
        evolution.addEzComponent(evolution_bounds, contour_resolution, contour_timeStep, convergence_winSize, convergence_operation, convergence_criterion, convergence_nbIter);
        addEzComponent(evolution);
        
        // output
        output_rois.setToolTipText("Select whether and where to export the contours as ROI for further quantification");
        addEzComponent(output_rois);
        output_roiType.setToolTipText("Select the type of ROI to export");
        addEzComponent(output_roiType);
        output_rois.addVisibilityTriggerTo(output_roiType, ExportROI.ON_INPUT, ExportROI.ON_NEW_IMAGE);
        
        tracking.setToolTipText("Track objects over time (no export)");
        addEzComponent(tracking);
        addEzComponent(tracking_newObjects);
        tracking.addVisibilityTriggerTo(tracking_newObjects, true);
        addEzComponent(volume_constraint);
        tracking.addVisibilityTriggerTo(volume_constraint, true);
        addEzComponent(showTrackManager);
        
        setTimeDisplay(true);
    }
    
    @Override
    public void execute()
    {
        volumes.clear();
        roiOutput.setValue(null);
        inputData = input.getValue(true);
        
        globalStop = false;
        
        int startT = inputData.getFirstViewer() == null ? 0 : inputData.getFirstViewer().getPositionT();
        int endT = tracking.getValue() ? inputData.getSizeT() - 1 : startT;
        
        trackGroup = new TrackGroup(inputData);
        trackGroup.setDescription("Active contours (" + new Date().toString() + ")");
        
        if (!Icy.getMainInterface().isHeadLess())
        {
            // replace any ActiveContours Painter object on the sequence by ours
            for (Overlay overlay : inputData.getOverlays())
                if (overlay instanceof ActiveContoursOverlay) overlay.remove();
            
            overlay = new ActiveContoursOverlay(trackGroup);
            overlay.setPriority(OverlayPriority.TOPMOST);
            inputData.addOverlay(overlay);
        }
        
        if (getUI() != null)
        {
            roiInput.setValue(new ROI[0]);
            
            if (inputData.getFirstViewer() != null)
            {
                startT = inputData.getFirstViewer().getPositionT();
            }
        }
        
        for (int t = startT; t <= endT; t++)
        {
            if (isHeadLess()) System.out.println("Processing frame #" + t);
            
            if (inputData.getFirstViewer() != null) inputData.getFirstViewer().setPositionT(t);
            
            if (isHeadLess()) System.out.println("=> retrieving image data...");
            initData(t, t == startT);
            
            if (isHeadLess()) System.out.println("=> creating contours...");
            initContours(t, t == startT);
            
            if (Thread.currentThread().isInterrupted()) break;
            
            // if (firstRun)
            // {
            // // the thread pool now is warmed up
            // // and the JIT did its business
            // // => restart at full speed
            // firstRun = false;
            // execute();
            // return;
            // }
            
            if (isHeadLess()) System.out.println("=> evolving contours...");
            
            // evolve contours on the current image
            evolveContours(t);
            
            if (Thread.currentThread().isInterrupted()) break;
            
            if (isHeadLess()) System.out.println("=> Storing result...");
            
            // store detections and results
            storeResult(t);
            
            if (tracking_newObjects.getValue())
            {
                // watch for entering objects
                
                ArrayList<ConnectedComponent> newObjects = new ArrayList<ConnectedComponent>();
                
                try
                {
                    // get the average contour volume to find new ones
                    
                    double vol = 0;
                    for (Double volume : volumes.values())
                        vol += volume;
                    vol /= volumes.values().size();
                    
                    Map<Integer, List<ConnectedComponent>> map = null;
                    Sequence currentT = SequenceUtil.extractFrame(inputData, t);
                    map = HierarchicalKMeans.hierarchicalKMeans(currentT, 4, 10, (int) vol / 2, (int) vol * 2, (Sequence) null);
                    newObjects.addAll(map.get(0));
                }
                catch (ConvolutionException e)
                {
                    // never mind
                }
                
                // 1) duplicate contours from the previous frame
                for (TrackSegment segment : trackGroup.getTrackSegmentList())
                {
                    Detection previous = segment.getDetectionAtTime(t);
                    
                    if (previous == null) continue;
                    
                    ActiveContour previousContour = (ActiveContour) previous;
                    
                    // remove any "new object" at that location"
                    for (int i = 0; i < newObjects.size(); i++)
                    {
                        Point3d center = newObjects.get(i).getMassCenter();
                        
                        if (previousContour.contains(center) > 0) newObjects.remove(i--);
                    }
                }
                
                // add new objects (2D only)
                if (inputData.getSizeZ() == 1)
                {
                    for (ConnectedComponent cc : newObjects)
                    {
                        ROI2DArea roi = new ROI2DArea((ROI2DArea) cc.toROI());
                        final SlidingWindow window = new SlidingWindow(convergence_winSize.getValue());
                        final ActiveContour contour = new Polygon2D(contour_resolution, window, roi);
                        contour.setX(roi.getBounds2D().getCenterX());
                        contour.setY(roi.getBounds2D().getCenterY());
                        contour.setZ(0);
                        contour.setT(t);
                        
                        TrackSegment segment = new TrackSegment();
                        segment.addDetection(contour);
                        synchronized (trackGroup)
                        {
                            trackGroup.addTrackSegment(segment);
                        }
                        synchronized (region_cin)
                        {
                            region_cin.put(segment, 0.0);
                        }
                        synchronized (region_cout)
                        {
                            region_cout.put(segment, 0.0);
                        }
                    }
                    
                    evolveContours(t);
                }
            }
            
            if (Thread.currentThread().isInterrupted()) break;
            
            if (globalStop) break;
            
        }
        
        if (getUI() != null)
        {
            getUI().setProgressBarValue(0.0);
            
            if (output_rois.getValue() != ExportROI.NO)
            {
                Sequence out = output_rois.getValue() == ExportROI.ON_INPUT ? inputData : SequenceUtil.getCopy(inputData);
                
                if (out != inputData) out.setName(inputData.getName() + " + Active contours");
                
                for (ROI roi : roiOutput.getValue())
                    out.addROI(roi, false);
                
                if (out != inputData) addSequence(out);
            }
        }
        else
        {
            // possibly block mode, remove the painter after processing
            // if (inputData != null) inputData.removePainter(painter);
        }
    }
    
    private void initData(int t, boolean isFirstFrame)
    {
        if (edge_c.getValue() >= inputData.getSizeC())
        {
            throw new IcyHandledException("The selected edge channel is invalid.");
        }
        
        if (region_c.getValue() >= inputData.getSizeC())
        {
            throw new IcyHandledException("The selected region channel is valid.");
        }
        
        // get the current frame (in its original data type)
        Sequence currentFrame = SequenceUtil.extractFrame(inputData, t);
        
        // extract the edge and region data, rescale to [0,1]
        edgeData = SequenceUtil.extractChannel(currentFrame, edge_c.getValue());
        edgeData = SequenceUtil.convertToType(edgeData, DataType.FLOAT, true, true);
        
        region_data = SequenceUtil.extractChannel(currentFrame, region_c.getValue());
        region_data = SequenceUtil.convertToType(region_data, DataType.FLOAT, true, true);
        
        // smooth the signal
        
        try
        {
            Sequence gaussian = Kernels1D.CUSTOM_GAUSSIAN.createGaussianKernel1D(1).toSequence();
            Convolution1D.convolve(edgeData, gaussian, gaussian, null);
            Convolution1D.convolve(region_data, gaussian, gaussian, null);
        }
        catch (ConvolutionException e)
        {
            System.err.println("Warning: error while smoothing the signal: " + e.getMessage());
        }
        
        // 1) Initialize the edge data
        {
            if (edge_c.getValue() == region_c.getValue())
            {
                // find edges within the region-based information
                // compute the gradient magnitude
                
                // put the X gradient in edgeData, and add the Y gradient into it
                Sequence gY = SequenceUtil.getCopy(edgeData);
                
                try
                {
                    Sequence gradient = Kernels1D.GRADIENT.toSequence();
                    // X
                    Convolution1D.convolve(edgeData, gradient, null, null);
                    // Y
                    Convolution1D.convolve(gY, null, gradient, null);
                    // TODO Z
                    
                    for (int z = 0; z < inputData.getSizeZ(); z++)
                    {
                        ArrayMath.abs(edgeData.getDataXYAsFloat(0, z, 0), true);
                        ArrayMath.abs(gY.getDataXYAsFloat(0, z, 0), true);
                        // add y into x
                        ArrayMath.add(edgeData.getDataXYAsFloat(0, z, 0), gY.getDataXYAsFloat(0, z, 0), edgeData.getDataXYAsFloat(0, z, 0));
                    }
                    
                    // rescale to [0-1]
                    edgeData.updateChannelsBounds(true);
                    edgeData = SequenceUtil.convertToType(edgeData, edgeData.getDataType_(), true, true);
                    // addSequence(edgeData);
                }
                catch (ConvolutionException e)
                {
                    throw new EzException("Cannot smooth the signal: " + e.getMessage(), true);
                }
            }
        }
        
        // 2) initialize the region data
        {
            // initialize the mask buffer (used to calculate average intensities inside/outside
            if (isFirstFrame)
            {
                contourMask_buffer = new Sequence("buffer");
                for (int z = 0; z < inputData.getSizeZ(); z++)
                    contourMask_buffer.setImage(0, z, new IcyBufferedImage(inputData.getWidth(), inputData.getHeight(), 1, DataType.USHORT));
            }
        }
    }
    
    private void initContours(final int t, boolean isFirstFrame)
    {
        if (isFirstFrame)
        {
            initFirstFrame(t);
        }
        else
        {
            // 1) duplicate contours from the previous frame
            for (TrackSegment segment : trackGroup.getTrackSegmentList())
            {
                Detection previous = segment.getDetectionAtTime(t - 1);
                
                if (previous == null) continue;
                
                ActiveContour previousContour = (ActiveContour) previous;
                
                previousContour.clean();
                
                ActiveContour clone = previousContour.clone();
                clone.convergence.setSize(convergence_winSize.getValue() * 2);
                clone.setT(t);
                segment.addDetection(clone);
                //
                // if (volumes.containsKey(segment))
                // {
                // // un-comment to store volumes after each frame
                // volumes.put(segment, ((ActiveContour) previous).getDimension(2));
                // }
                // else
                // {
                // // new volume (after first image was processed)
                // volumes.put(segment, clone.getDimension(2));
                // }
            }
        }
    }
    
    private void initFirstFrame(final int t)
    {
        final int depth = inputData.getSizeZ();
        
        if (roiInput.getValue().length == 0)
        {
            if (isHeadLess()) throw new VarException("Active contours: no input ROI");
            
            ArrayList<ROI> roiFromSequence = inputData.getROIs();
            
            if (roiFromSequence.isEmpty()) throw new EzException("Please draw or select a ROI", true);
            
            // only pick ROI in all or the current frame
            for (int i = 0; i < roiFromSequence.size(); i++)
            {
                ROI roi = roiFromSequence.get(i);
                
                if (roi instanceof ROI2D)
                {
                    ROI2D r2 = (ROI2D) roi;
                    if (r2.getT() != -1 && r2.getT() != t) roiFromSequence.remove(i--);
                }
                else if (roi instanceof ROI3D)
                {
                    ROI3D r3 = (ROI3D) roi;
                    if (r3.getT() != -1 && r3.getT() != t) roiFromSequence.remove(i--);
                }
                else roiFromSequence.remove(i--);
            }
            
            roiInput.setValue(roiFromSequence.toArray(new ROI[roiFromSequence.size()]));
        }
        
        ArrayList<Future<?>> tasks = new ArrayList<Future<?>>(roiInput.getValue().length);
        
        for (ROI roi : roiInput.getValue())
        {
            if (roi instanceof ROI2D)
            {
                final ROI2D roi2d = (ROI2D) roi;
                
                final int realZ;
                if (roi2d.getZ() == -1)
                {
                    // a 2D contour cannot be created from a "virtually 3D" ROI,
                    // merely a slice of it => which one?
                    if (getUI() != null)
                    {
                        realZ = inputData.getFirstViewer().getPositionZ();
                    }
                    else
                    {
                        if (depth > 1)
                        {
                            System.err.println("WARNING: cannot create a 2D contour from a ROI of infinite Z dimension, will use Z=0");
                        }
                        realZ = 0;
                    }
                }
                else
                {
                    realZ = roi2d.getZ();
                }
                
                Runnable initializer = new Runnable()
                {
                    public void run()
                    {
                        if (roi2d instanceof ROI2DArea)
                        {
                            // special case: check if the area has multiple components => split them
                            ROI2DArea area = (ROI2DArea) roi2d;
                            
                            BooleanMask2D[] components = area.getBooleanMask(true).getComponents();
                            
                            for (BooleanMask2D comp : components)
                            {
                                ROI2DArea roi = new ROI2DArea(comp);
                                roi.setZ(realZ);
                                
                                final SlidingWindow window = new SlidingWindow(convergence_winSize.getValue());
                                final ActiveContour contour = new Polygon2D(contour_resolution, window, roi);
                                contour.setT(t);
                                
                                TrackSegment segment = new TrackSegment();
                                segment.addDetection(contour);
                                synchronized (trackGroup)
                                {
                                    trackGroup.addTrackSegment(segment);
                                }
                                synchronized (region_cin)
                                {
                                    region_cin.put(segment, 0.0);
                                }
                                synchronized (region_cout)
                                {
                                    region_cout.put(segment, 0.0);
                                }
                            }
                        }
                        else
                        {
                            final SlidingWindow window = new SlidingWindow(convergence_winSize.getValue());
                            final ActiveContour contour = new Polygon2D(contour_resolution, window, roi2d);
                            contour.setX(roi2d.getBounds2D().getCenterX());
                            contour.setY(roi2d.getBounds2D().getCenterY());
                            contour.setZ(realZ);
                            contour.setT(t);
                            
                            TrackSegment segment = new TrackSegment();
                            segment.addDetection(contour);
                            synchronized (trackGroup)
                            {
                                trackGroup.addTrackSegment(segment);
                            }
                            synchronized (region_cin)
                            {
                                region_cin.put(segment, 0.0);
                            }
                            synchronized (region_cout)
                            {
                                region_cout.put(segment, 0.0);
                            }
                        }
                    }
                };
                
                tasks.add(multiThreadService.submit(initializer));
            }
            else if (roi instanceof ROI3D)
            {
                final ROI3D r3 = (ROI3D) roi;
                
                Runnable initializer = new Runnable()
                {
                    public void run()
                    {
                        if (r3 instanceof ROI3DArea)
                        {
                            // TODO special case: split if the area has multiple components
                            ROI3DArea area3D = (ROI3DArea) r3;
                            
                            final SlidingWindow window = new SlidingWindow(convergence_winSize.getValue());
                            final ActiveContour contour = new Mesh3D(inputData.getPixelSizeX(), inputData.getPixelSizeZ(), contour_resolution, window, r3);
                            contour.setX(r3.getBounds3D().getCenterX());
                            contour.setY(r3.getBounds3D().getCenterY());
                            contour.setT(t);
                            
                            // contour.toSequence(inputData, 3000);
                            
                            TrackSegment segment = new TrackSegment();
                            segment.addDetection(contour);
                            synchronized (trackGroup)
                            {
                                trackGroup.addTrackSegment(segment);
                            }
                            synchronized (region_cin)
                            {
                                region_cin.put(segment, 0.0);
                            }
                            synchronized (region_cout)
                            {
                                region_cout.put(segment, 0.0);
                            }
                        }
                        else
                        {
                            System.out.println("Skipping non-area-like 3D ROI");
                        }
                    }
                };
                
                tasks.add(multiThreadService.submit(initializer));
            }
        }
        
        try
        {
            for (Future<?> future : tasks)
                future.get();
        }
        catch (InterruptedException e)
        {
            // restore the interrupted flag
            Thread.currentThread().interrupt();
            return;
        }
        catch (Exception e)
        {
            if (e.getCause() instanceof EzException) throw (EzException) e.getCause();
            e.printStackTrace();
        }
    }
    
    public void evolveContours(final int t)
    {
        // retrieve the contours on the current frame and store them in currentContours
        final HashSet<ActiveContour> allContours = new HashSet<ActiveContour>(trackGroup.getTrackSegmentList().size());
        
        for (TrackSegment segment : trackGroup.getTrackSegmentList())
        {
            Detection det = segment.getDetectionAtTime(t);
            if (det != null) allContours.add((ActiveContour) det);
        }
        
        if (allContours.size() == 0) return;
        
        // get the bounded field of evolution
        ROI field;
        
        Sequence boundSource = evolution_bounds.getValue();
        
        if (boundSource != null && boundSource.getROIs().size() > 0)
        {
            field = ROIUtil.merge(boundSource.getROIs(), BooleanOperator.OR);
        }
        else if (inputData.getSizeZ() == 1)
        {
            field = new ROI2DRectangle(0, 0, inputData.getWidth(), inputData.getHeight());
        }
        else
        {
            ROI3DStack<ROI2DRectangle> field3D = new ROI3DStack<ROI2DRectangle>(ROI2DRectangle.class);
            for (int z = 0; z < inputData.getSizeZ(); z++)
                field3D.setSlice(z, new ROI2DRectangle(0, 0, inputData.getWidth(), inputData.getHeight()));
            field = field3D;
        }
        
        int iter = 0;
        int nbConvergedContours = 0;
        
        final HashSet<ActiveContour> evolvingContours = new HashSet<ActiveContour>(allContours.size());
        
        while (!globalStop && nbConvergedContours < allContours.size())
        {
            nbConvergedContours = 0;
            
            // take a snapshot of the current list of evolving (i.e. non-converged) contours
            evolvingContours.clear();
            for (ActiveContour contour : allContours)
            {
                Double criterion = contour.convergence.computeCriterion(convergence_operation.getValue());
                
                if (criterion != null && criterion <= convergence_criterion.getValue() / 100)
                {
                    nbConvergedContours++;
                    continue;
                }
                
                // safeguard
                // if (iter > 1000 * contour.convergence.getSize())
                // {
                // nbConvergedContours++;
                // continue;
                // }
                
                // if the contour hasn't converged yet, store it for the main loop
                evolvingContours.add(contour);
            }
            
            if (getUI() != null)
            {
                getUI().setProgressBarValue((double) nbConvergedContours / allContours.size());
                // getUI().setProgressBarMessage("" + iter); // slows down the AWT !!
            }
            
            if (evolvingContours.size() == 0) break;
            
            // re-sample the contours to ensure homogeneous resolution
            resampleContours(evolvingContours, allContours, t);
            
            // update region information (if necessary):
            // - every 10 iterations
            // if the contour list has changed
            
            if (region_weight.getValue() > EPSILON)
            {
                boolean updateRegionStatistics = iter % (convergence_winSize.getValue() / 3) == 0;
                
                for (ActiveContour contour : allContours)
                {
                    // make sure this contour's statistics exist
                    if (region_cout.containsKey(trackGroup.getTrackSegmentWithDetection(contour))) continue;
                    
                    updateRegionStatistics = true;
                    break;
                }
                
                if (updateRegionStatistics) updateRegionInformation(allContours, t);
            }
            
            // compute deformations issued from the energy minimization
            deformContours(evolvingContours, allContours, field);
            
            // compute energy
            // computeEnergy(mainService, allContours);
            
            if (Thread.currentThread().isInterrupted()) globalStop = true;
            
            if (!Icy.getMainInterface().isHeadLess())
            {
                overlay.painterChanged();
            }
            
            if (iter > convergence_nbIter.getValue())
            {
                System.out.println("[Active Contours] Converged on frame " + t + " in " + iter + " iterations");
                return;
            }
            
            iter++;
        }
        
        System.out.println("[Active Contours] Converged on frame " + t + " in " + iter + " iterations");
    }
    
    /**
     * Deform contours together (coupling involved)
     * 
     * @param service
     * @param evolvingContours
     * @param allContours
     */
    public void deformContours(final HashSet<ActiveContour> evolvingContours, final HashSet<ActiveContour> allContours, final ROI field)
    {
        if (evolvingContours.size() == 1 && allContours.size() == 1)
        {
            // no multi-threading needed
            
            ActiveContour contour = evolvingContours.iterator().next();
            TrackSegment segment = trackGroup.getTrackSegmentWithDetection(contour);
            
            if (Math.abs(edge_weight.getValue()) > EPSILON)
            {
                contour.computeEdgeForces(edgeData, 0, edge_weight.getValue());
            }
            
            if (regul_weight.getValue() > EPSILON)
            {
                contour.computeInternalForces(regul_weight.getValue());
            }
            
            if (region_weight.getValue() > EPSILON)
            {
                contour.computeRegionForces(region_data, 0, region_weight.getValue(), region_sensitivity.getValue(), region_cin.get(segment), region_cout.get(segment));
            }
            
            if (axis_weight.getValue() > EPSILON)
            {
                contour.computeAxisForces(axis_weight.getValue());
            }
            
            if (Math.abs(balloon_weight.getValue()) > EPSILON)
            {
                contour.computeBalloonForces(balloon_weight.getValue());
            }
            
            if (volume_constraint.getValue() && volumes.containsKey(segment))
            {
                contour.computeVolumeConstraint(volumes.get(segment));
            }
            
            contour.move(field, contour_timeStep.getValue());
        }
        else
        {
            ArrayList<Callable<ActiveContour>> tasks = new ArrayList<Callable<ActiveContour>>(evolvingContours.size());
            
            for (final ActiveContour contour : evolvingContours)
            {
                tasks.add(new Callable<ActiveContour>()
                {
                    public ActiveContour call()
                    {
                        TrackSegment segment = trackGroup.getTrackSegmentWithDetection(contour);
                        
                        if (regul_weight.getValue() > EPSILON)
                        {
                            contour.computeInternalForces(regul_weight.getValue());
                        }
                        
                        if (Math.abs(edge_weight.getValue()) > EPSILON)
                        {
                            contour.computeEdgeForces(edgeData, 0, edge_weight.getValue());
                        }
                        
                        if (region_weight.getValue() > EPSILON)
                        {
                            if (!region_cin.containsKey(segment))
                            {
                                region_cin.put(segment, contour.computeAverageIntensity(region_data, 0, contourMask_buffer));
                            }
                            if (!region_cout.containsKey(segment))
                            {
                                region_cout.put(segment, 0.0);
                            }
                            contour.computeRegionForces(region_data, 0, region_weight.getValue(), region_sensitivity.getValue(), region_cin.get(segment), region_cout.get(segment));
                        }
                        
                        if (axis_weight.getValue() > EPSILON)
                        {
                            contour.computeAxisForces(axis_weight.getValue());
                        }
                        
                        if (Math.abs(balloon_weight.getValue()) > EPSILON)
                        {
                            contour.computeBalloonForces(balloon_weight.getValue());
                        }
                        
                        if (volume_constraint.getValue() && volumes.containsKey(segment))
                        {
                            contour.computeVolumeConstraint(volumes.get(segment));
                        }
                        
                        if (coupling_flag.getValue())
                        {
                            // Don't move the contours just now: coupling feedback must be computed
                            // against ALL contours (including those which have already converged)
                            for (ActiveContour otherContour : allContours)
                            {
                                if (otherContour == null || otherContour == contour) continue;
                                
                                contour.computeFeedbackForces(otherContour);
                            }
                        }
                        else
                        {
                            // move contours asynchronously
                            contour.move(field, contour_timeStep.getValue());
                        }
                        
                        return contour;
                    }
                });
            }
            
            try
            {
                for (Future<ActiveContour> future : multiThreadService.invokeAll(tasks))
                    try
                    {
                        future.get();
                    }
                    catch (ExecutionException e)
                    {
                        e.printStackTrace();
                        throw new RuntimeException(e);
                    }
            }
            catch (InterruptedException e)
            {
                // reset the interrupted flag
                Thread.currentThread().interrupt();
                return;
            }
            
            if (coupling_flag.getValue())
            {
                // motion is synchronous, and can be done now
                for (ActiveContour contour : evolvingContours)
                    contour.move(field, contour_timeStep.getValue());
            }
        }
    }
    
    /**
     * Resample all contours to maintain a homogeneous resoltution
     * 
     * @param evolvingContours
     * @param allContours
     * @param t
     * @return <code>true</code> if the list of contours has changed (a contour has vanished or
     *         divided), <code>false</code> otherwise
     */
    private void resampleContours(final HashSet<ActiveContour> evolvingContours, final HashSet<ActiveContour> allContours, final int t)
    {
        final VarBoolean loop = new VarBoolean("loop", true);
        
        final VarBoolean change = new VarBoolean("change", false);
        
        while (loop.getValue())
        {
            loop.setValue(false);
            
            if (evolvingContours.size() == 1)
            {
                // no multi-threading needed
                Iterator<ActiveContour> iterator = evolvingContours.iterator();
                if (iterator.hasNext())
                {
                    ActiveContour contour = evolvingContours.iterator().next();
                    ReSampler reSampler = new ReSampler(trackGroup, contour, evolvingContours, allContours);
                    if (reSampler.call())
                    {
                        change.setValue(true);
                        loop.setValue(true);
                    }
                }
            }
            else
            {
                ArrayList<ReSampler> tasks = new ArrayList<ReSampler>(evolvingContours.size());
                
                for (final ActiveContour contour : evolvingContours)
                    tasks.add(new ReSampler(trackGroup, contour, evolvingContours, allContours));
                
                try
                {
                    for (Future<Boolean> resampled : multiThreadService.invokeAll(tasks))
                    {
                        if (resampled.get())
                        {
                            change.setValue(true);
                            loop.setValue(true);
                        }
                    }
                }
                catch (InterruptedException e)
                {
                    // reset the interrupted flag
                    Thread.currentThread().interrupt();
                }
                catch (ExecutionException e)
                {
                    e.getCause().printStackTrace();
                    throw new RuntimeException(e);
                }
                catch (RuntimeException e)
                {
                    throw e;
                }
                finally
                {
                    tasks.clear();
                }
            }
        }
    }
    
    private void updateRegionInformation(HashSet<ActiveContour> contours, int t)
    {
        ArrayList<Future<?>> tasks = new ArrayList<Future<?>>(contours.size());
        
        if (contours.size() == 1)
        {
            ActiveContour contour = contours.iterator().next();
            TrackSegment segment = trackGroup.getTrackSegmentWithDetection(contour);
            
            // only update on the first contour of the segment (first time point)
            // if (segment.getFirstDetection() != contour) return;
            
            double cin = contour.computeAverageIntensity(region_data, 0, contourMask_buffer);
            synchronized (region_cin)
            {
                region_cin.put(segment, cin);
                // System.out.print("in: " + cin);
            }
        }
        else
        {
            for (final ActiveContour contour : contours)
                tasks.add(multiThreadService.submit(new Runnable()
                {
                    public void run()
                    {
                        TrackSegment segment = trackGroup.getTrackSegmentWithDetection(contour);
                        
                        // only update on the first contour of the segment (first time point)
                        // if (segment.getFirstDetection() != contour) return;
                        
                        double cin = contour.computeAverageIntensity(region_data, 0, contourMask_buffer);
                        synchronized (region_cin)
                        {
                            region_cin.put(segment, cin);
                        }
                    }
                }));
            
            try
            {
                for (Future<?> task : tasks)
                    task.get();
            }
            catch (InterruptedException e)
            {
                // reset the interrupted flag
                Thread.currentThread().interrupt();
                return;
            }
            catch (ExecutionException e)
            {
                e.getCause().printStackTrace();
                throw new RuntimeException(e);
            }
        }
        
        int sizeZ = contourMask_buffer.getSizeZ();
        
        double[] outs = new double[sizeZ];
        
        for (int z = 0; z < sizeZ; z++)
        {
            double outSumSlice = 0, outCptSlice = 0;
            
            short[] _mask = contourMask_buffer.getDataXYAsShort(0, z, 0);
            float[] _data = region_data.getDataXYAsFloat(0, z, 0);
            
            for (int i = 0; i < _mask.length; i++)
            {
                if (_mask[i] == 0)
                {
                    double value = _data[i];
                    outSumSlice += value;
                    outCptSlice++;
                }
                else
                {
                    // erase the mask for the next iteration
                    _mask[i] = 0;
                }
            }
            
            outs[z] = outSumSlice / outCptSlice;
        }
        
        for (ActiveContour contour : contours)
        {
            TrackSegment segment = trackGroup.getTrackSegmentWithDetection(contour);
            if (contour instanceof Polygon2D)
            {
                double cout = outs[(int) Math.round(contour.getZ())];
                region_cout.put(segment, cout);
                // System.out.println("  out: " + cout);
            }
            else
            {
                double cout = ArrayMath.mean(outs);
                region_cout.put(segment, cout);
                // System.out.println("  out: " + cout);
            }
        }
        
    }
    
    private void storeResult(int t)
    {
        ArrayList<TrackSegment> segments = trackGroup.getTrackSegmentList();
        
        ArrayList<ROI> rois = null;
        // Append the current list to the existing one
        rois = new ArrayList<ROI>(Arrays.asList(roiOutput.getValue()));
        
        int nbPaddingDigits = (int) Math.floor(Math.log10(segments.size()));
        
        // Sequence binSeq = new Sequence("Labeled output");
        
        // binSeq.setImage(t, 0, new IcyBufferedImage(inputData.getSizeX(), inputData.getSizeY(), 1,
        // DataType.USHORT));
        
        for (int i = 1; i <= segments.size(); i++)
        {
            TrackSegment segment = segments.get(i - 1);
            
            ActiveContour contour = (ActiveContour) segment.getDetectionAtTime(t);
            if (contour == null) continue;
            
            // temporary fix: indicate correct surface area in the console
            if (contour instanceof Mesh3D) System.out.print("Mesh #" + i + ": surface area = " + contour.getDimension(1));
            
            volumes.put(segment, contour.getDimension(2));
            
            // output as ROIs
            try
            {
                ROI roi = contour.toROI(output_roiType.getValue(), inputData);
                if (roi != null)
                {
                    roi.setName("Object #" + StringUtil.toString(i, nbPaddingDigits + 1));
                    roi.setColor(contour.getColor());
                    rois.add(roi);
                }
            }
            catch (UnsupportedOperationException unsupported)
            {
                throw new IcyHandledException("3D meshes cannot be exported as polygons (yet). Please select \"Area\" instead.");
            }
            // raster data
            // contour.toSequence(binSeq, i);
        }
        
        // if (!Icy.isHeadLess() && t==1) addSequence(binSeq);
        
        if (rois.size() > 0) roiOutput.setValue(rois.toArray(new ROI[0]));
        
    }
    
    @Override
    public void clean()
    {
        if (inputData != null) inputData.removeOverlay(overlay);
        
        // contoursMap.clear();
        // contours.clear();
        // trackGroup.clearTracks();
        if (region_weight.getValue() > EPSILON) region_cin.clear();
        
        // meanUpdateService.shutdownNow();
        multiThreadService.shutdownNow();
    }
    
    @Override
    public void stopExecution()
    {
        globalStop = true;
    }
    
    @Override
    public void declareInput(VarList inputMap)
    {
        inputMap.add("input sequence", input.getVariable());
        inputMap.add("Input ROI", roiInput);
        inputMap.add("regularization: weight", regul_weight.getVariable());
        inputMap.add("edge: weight", edge_weight.getVariable());
        edge_c.setActive(false);
        edge_c.setValues(0, 0, 16, 1);
        inputMap.add("edge: channel", edge_c.getVariable());
        inputMap.add("region: weight", region_weight.getVariable());
        inputMap.add("region: sensitivity", region_sensitivity.getVariable());
        region_c.setActive(false);
        region_c.setValues(0, 0, 16, 1);
        inputMap.add("region: channel", region_c.getVariable());
        
        inputMap.add("balloon: weight", balloon_weight.getVariable());
        
        coupling_flag.setValue(true);
        inputMap.add("contour resolution", contour_resolution.getVariable());
        contour_resolution.addVarChangeListener(new EzVarListener<Double>()
        {
            @Override
            public void variableChanged(EzVar<Double> source, Double newValue)
            {
                convergence_winSize.setValue((int) (100.0 / newValue));
            }
        });
        
        // inputMap.add("minimum object size", contour_minArea.getVariable());
        evolution_bounds.getVariable().setNoSequenceSelection();
        inputMap.add("region bounds", evolution_bounds.getVariable());
        inputMap.add("time step", contour_timeStep.getVariable());
        // inputMap.add("convergence window size", convergence_winSize.getVariable());
        inputMap.add("convergence value", convergence_criterion.getVariable());
        inputMap.add("max. iterations", convergence_nbIter.getVariable());
        inputMap.add("type of ROI output", output_roiType.getVariable());
        inputMap.add("tracking", tracking.getVariable());
        inputMap.add("volume constraint", volume_constraint.getVariable());
        inputMap.add("watch entering objects", tracking_newObjects.getVariable());
    }
    
    @Override
    public void declareOutput(VarList outputMap)
    {
        outputMap.add(roiOutput);
    }
    
}
