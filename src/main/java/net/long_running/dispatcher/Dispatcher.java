package net.long_running.dispatcher;

import static net.long_running.dispatcher.impl.PositionUtil.*;
import static net.long_running.dispatcher.AsyncCompletionCallback.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import net.long_running.dispatcher.impl.DispatcherContext;
import net.long_running.dispatcher.impl.Subscription;
import net.long_running.dispatcher.impl.log.LogBuffer;
import net.long_running.dispatcher.impl.log.LogAppender;
import net.long_running.dispatcher.impl.log.LogBufferPartition;
import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.concurrent.status.Position;

/**
 * Component for sending and receiving messages between different threads.
 *
 */
public class Dispatcher
{
    public final static int STATUS_NEW = 1;
    public final static int STATUS_ACTIVE = 2;
    public final static int STATUS_CLOSE_REQUESTED = 3;
    public final static int STATUS_CLOSED = 4;

    static final AtomicIntegerFieldUpdater<Dispatcher> STATE_UPDATER
        = AtomicIntegerFieldUpdater.newUpdater(Dispatcher.class, "state");

    protected volatile int state = STATUS_NEW;

    final DispatcherContext context;

    protected final LogBuffer logBuffer;
    protected final LogAppender logAppender;

    protected final Position publisherLimit;
    protected final Position publisherPosition;
    protected final Subscription[] subscriptions;

    protected final int maxFrameLength;
    protected final int partitionSize;
    protected final int initialPartitionId;
    protected int logWindowLength;

    Dispatcher(
            LogBuffer logBuffer,
            LogAppender logAppender,
            Position publisherLimit,
            Position publisherPosition,
            Position[] subscriberPositions,
            int logWindowLength,
            DispatcherContext context)
    {
        this.logBuffer = logBuffer;
        this.logAppender = logAppender;
        this.publisherLimit = publisherLimit;
        this.publisherPosition = publisherPosition;
        this.logWindowLength = logWindowLength;
        this.context = context;

        this.initialPartitionId = logBuffer.getInitialPartitionId();
        this.partitionSize = logBuffer.getPartitionSize();
        this.maxFrameLength = partitionSize / 16;

        subscriptions = new Subscription[subscriberPositions.length];

        for(int i = 0; i < subscriberPositions.length; i++)
        {
            subscriptions[i] = createSubscription(subscriberPositions[i]);
        }
    }

    protected Subscription createSubscription(Position subscriberPosition)
    {
        return new Subscription(subscriberPosition);
    }

    public void start(AsyncCompletionCallback<Dispatcher> startCallback)
    {
        context.getDispatcherCommandQueue()
            .add((conductor) -> conductor.requestStartDispatcher(this, startCallback));
    }

    public void startSync() throws InterruptedException
    {
        final CompletableFuture<Dispatcher> future = new CompletableFuture<>();

        start(completeFuture(future));

        try
        {
            future.get();
        }
        catch (ExecutionException e)
        {
            if(e.getCause() instanceof RuntimeException)
            {
                throw (RuntimeException) e.getCause();
            }
            else
            {
                throw new RuntimeException("Exception while waiting for dispatcher to start", e.getCause());
            }
        }
    }

    public long offer(DirectBuffer msg)
    {
        return offer(msg, 0, msg.capacity(), 0);
    }

    public long offer(DirectBuffer msg, int start, int length)
    {
        return offer(msg, start, length, 0);
    }

    public long offer(DirectBuffer msg, int start, int length, int streamId)
    {
        final long limit = publisherLimit.getVolatile();

        final int initialPartitionId = logBuffer.getInitialPartitionId();
        final int activePartitionId = logBuffer.getActivePartitionIdVolatile();
        final int partitionIndex = (activePartitionId - initialPartitionId) % logBuffer.getPartitionCount();
        final LogBufferPartition partition = logBuffer.getPartition(partitionIndex);

        final int partitionOffset = partition.getTailCounterVolatile();
        final long position = position(activePartitionId, partitionOffset);

        long newPosition = -1;

        if(position < limit)
        {
            int newOffset;

            if (length < maxFrameLength)
            {
                newOffset = logAppender.appendFrame(partition,
                        activePartitionId,
                        msg,
                        start,
                        length,
                        streamId);
            }
            else
            {
                throw new RuntimeException("Message length of "+length+" is larger than max frame length of "+maxFrameLength);
            }

            newPosition = updatePublisherPosition(initialPartitionId, activePartitionId, newOffset);

            publisherPosition.proposeMaxOrdered(newPosition);
        }
        return newPosition;
    }

    public long claim(ClaimedFragment claim, int length)
    {
        return claim(claim, length, 0);
    }

    public long claim(ClaimedFragment claim, int length, int streamId)
    {

        final long limit = publisherLimit.getVolatile();

        final int initialPartitionId = logBuffer.getInitialPartitionId();
        final int activePartitionId = logBuffer.getActivePartitionIdVolatile();
        final int partitionIndex = (activePartitionId - initialPartitionId) % logBuffer.getPartitionCount();
        final LogBufferPartition partition = logBuffer.getPartition(partitionIndex);

        final int partitionOffset = partition.getTailCounterVolatile();
        final long position = position(activePartitionId, partitionOffset);

        long newPosition = -1;

        if(position < limit)
        {
            int newOffset;

            if (length < maxFrameLength)
            {
                newOffset = logAppender.claim(partition,
                        activePartitionId,
                        claim,
                        length,
                        streamId);
            }
            else
            {
                throw new RuntimeException("Cannot claim more than "+maxFrameLength+ " bytes.");
            }

            newPosition = updatePublisherPosition(initialPartitionId, activePartitionId, newOffset);

            publisherPosition.proposeMaxOrdered(newPosition);
        }

        return newPosition;

    }

    protected long updatePublisherPosition(final int initialPartitionId, final int activePartitionId, int newOffset)
    {
        long newPosition = -1;

        if(newOffset > 0)
        {
            newPosition = position(activePartitionId, newOffset);
        }
        else if(newOffset == -2)
        {
            logBuffer.onActiveParitionFilled(activePartitionId);
            newPosition = -2;
        }

        return newPosition;
    }

    public int poll(FragmentHandler frgHandler, int maxNumOfFragments)
    {
        return poll(0, frgHandler, maxNumOfFragments);
    }

    public int poll(int subscriberId, FragmentHandler frgHandler, int maxNumOfFragments)
    {
        int fragmentsRead = 0;

        final Subscription subscription = subscriptions[subscriberId];
        final long currentPosition = subscription.getPosition();

        final long limit = subscriberLimit(subscriberId, publisherPosition, subscriptions);

        if(limit > currentPosition)
        {
            final int partitionId = partitionId(currentPosition);
            final int partitionOffset = partitionOffset(currentPosition);

            final LogBufferPartition partition = logBuffer.getPartition(partitionId % logBuffer.getPartitionCount());

            fragmentsRead = subscription.pollFragments(partition,
                    frgHandler,
                    maxNumOfFragments,
                    partitionId,
                    partitionOffset);
        }

        return fragmentsRead;
    }

    public int pollBlock(BlockHandler blockHandler, int maxNumOfFragments)
    {
        return pollBlock(0, blockHandler, maxNumOfFragments, false);
    }

    public int pollBlock(BlockHandler blockHandler, int maxNumOfFragments, boolean isStreamAware)
    {
        return pollBlock(0, blockHandler, maxNumOfFragments, isStreamAware);
    }

    public int pollBlock(int subscriberId, BlockHandler blockHandler, int maxNumOfFragments, boolean isStreamAware)
    {
        int fragmentsRead = 0;

        final Subscription subscription = subscriptions[subscriberId];
        final long currentPosition = subscription.getPosition();

        final long limit = subscriberLimit(subscriberId, publisherPosition, subscriptions);

        if(limit > currentPosition)
        {
            final int partitionId = partitionId(currentPosition);
            final int partitionOffset = partitionOffset(currentPosition);

            final LogBufferPartition partition = logBuffer.getPartition(partitionId % logBuffer.getPartitionCount());

            fragmentsRead = subscription.pollBlock(partition,
                    blockHandler,
                    maxNumOfFragments,
                    partitionId,
                    partitionOffset,
                    isStreamAware);
        }

        return fragmentsRead;
    }

    private static long subscriberLimit(
            int subscriberId,
            Position publisherPosition,
            Subscription[] subscriptions)
    {
        long limit = -1;

        if(subscriberId == 0)
        {
            limit = publisherPosition.get();
        }
        else
        {
            limit = subscriptions[subscriberId - 1].getPosition();
        }

        return limit;
    }

    public void close() throws InterruptedException
    {
        final CompletableFuture<Dispatcher> future = new CompletableFuture<>();

        closeAsync(completeFuture(future));

        try
        {
            future.get();
        }
        catch (ExecutionException e)
        {
            Throwable cause = e.getCause();
            if(cause instanceof RuntimeException)
            {
                throw (RuntimeException) cause;
            }
            else
            {
                throw new RuntimeException("Exception while waiting for dispatcher to close", e);
            }
        }
    }

    public void closeAsync(AsyncCompletionCallback<Dispatcher> closeCallback)
    {
        context.getDispatcherCommandQueue()
          .add((conductor) -> conductor.requestCloseDispatcher(this, closeCallback));
    }

    public int updatePublisherLimit()
    {
        final long lastSubscriberPosition = subscriptions[subscriptions.length -1].getPosition();
        int partitionId = partitionId(lastSubscriberPosition);
        int partitionOffset = partitionOffset(lastSubscriberPosition) + logWindowLength;
        if(partitionOffset >= logBuffer.getPartitionSize())
        {
            ++partitionId;
            partitionOffset = logWindowLength;

        }
        final long proposedPublisherLimit = position(partitionId, partitionOffset);

        if(publisherLimit.proposeMaxOrdered(proposedPublisherLimit))
        {
            return 1;
        }

        return 0;
    }

    public LogBuffer getLogBuffer()
    {
        return logBuffer;
    }

    public int getStatus()
    {
        return state;
    }

    public void setStateOrdered(int state)
    {
        STATE_UPDATER.lazySet(this, state);
    }

    public void setPublisherLimitOrdered(int limit)
    {
        this.publisherLimit.setOrdered(limit);

    }

    public boolean isReadyToClose()
    {
        final long lastSubscriberPosition = subscriptions[subscriptions.length -1].getPosition();
        return lastSubscriberPosition == publisherPosition.getVolatile();
    }

    public void doClose()
    {
      setStateOrdered(STATUS_CLOSED);

      publisherLimit.close();
      publisherPosition.close();

      for (Subscription subscription : subscriptions)
      {
          subscription.close();
      }

      logBuffer.close();
    }

    public int getMaxFrameLength()
    {
        return maxFrameLength;
    }

}
