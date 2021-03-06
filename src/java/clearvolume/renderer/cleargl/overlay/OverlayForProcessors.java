package clearvolume.renderer.cleargl.overlay;

import java.util.ArrayList;

import cleargl.GLMatrix;
import clearvolume.renderer.DisplayRequestInterface;
import clearvolume.renderer.processors.ProcessorInterface;

import com.jogamp.opengl.GL;

public class OverlayForProcessors extends OverlayBase	implements
																											Overlay2D,
																											Overlay3D
{

	private final ArrayList<ProcessorInterface<?>> mProcessorInterfaces = new ArrayList<ProcessorInterface<?>>();

	private final Overlay mOverlay;

	public OverlayForProcessors(Overlay Overlay)
	{
		mOverlay = Overlay;
	}

	public Overlay getDelegatedOverlay()
	{
		return mOverlay;
	}

	public void addProcessor(ProcessorInterface<?> pProcessor)
	{
		mProcessorInterfaces.add(pProcessor);
	}

	public void removeProcessor(ProcessorInterface<?> pProcessor)
	{
		mProcessorInterfaces.remove(pProcessor);
	}

	public ArrayList<ProcessorInterface<?>> getProcessorInterfaces()
	{
		return mProcessorInterfaces;
	}

	@Override
	public boolean toggle()
	{
		final boolean lNewState = getDelegatedOverlay().toggle();
		for (final ProcessorInterface<?> lProcessor : mProcessorInterfaces)
		{
			lProcessor.toggle();
		}
		return lNewState;
	}

	@Override
	public boolean isDisplayed()
	{
		return getDelegatedOverlay().isDisplayed();
	}

	@Override
	public String getName()
	{
		return mOverlay.getName();
	}

	@Override
	public void init(	GL pGL,
										DisplayRequestInterface pDisplayRequestInterface)
	{
		mOverlay.init(pGL, pDisplayRequestInterface);
	}

	@Override
	public boolean hasChanged3D()
	{
		if (mOverlay instanceof Overlay3D)
		{
			final Overlay3D lOverlay3D = (Overlay3D) mOverlay;
			return lOverlay3D.hasChanged3D();
		}
		return false;
	}

	@Override
	public void render3D(	GL pGL,
												int pWidth,
												int pHeight,
												GLMatrix pProjectionMatrix,
												GLMatrix pModelViewMatrix)
	{
		if (mOverlay instanceof Overlay3D)
		{
			final Overlay3D lOverlay3D = (Overlay3D) mOverlay;
			lOverlay3D.render3D(pGL,
													pWidth,
													pHeight,
													pProjectionMatrix,
													pModelViewMatrix);
		}
	}

	@Override
	public boolean hasChanged2D()
	{
		if (mOverlay instanceof Overlay2D)
		{
			final Overlay2D lOverlay2D = (Overlay2D) mOverlay;
			return lOverlay2D.hasChanged2D();
		}
		return false;
	}

	@Override
	public void render2D(	GL pGL,
												int pWidth,
												int pHeight,
												GLMatrix pProjectionMatrix)
	{
		if (mOverlay instanceof Overlay2D)
		{
			final Overlay2D lOverlay2D = (Overlay2D) mOverlay;
			lOverlay2D.render2D(pGL, pWidth, pHeight, pProjectionMatrix);
		}
	}

}
