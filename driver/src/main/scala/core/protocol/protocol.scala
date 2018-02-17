/*
 * Copyright 2012-2013 Stephane Godbillon (@sgodbillon) and Zenexity
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivemongo.core.protocol

import java.nio.ByteOrder

import scala.util.{ Failure, Success, Try }

import akka.actor.ActorRef

import shaded.netty.buffer._
import shaded.netty.channel._
import shaded.netty.handler.codec.oneone._
import shaded.netty.handler.codec.frame.FrameDecoder
import shaded.netty.handler.timeout.{
  IdleStateEvent,
  IdleStateAwareChannelHandler
}

import reactivemongo.bson.{ BSONBooleanLike, BSONDocument, BSONNumberLike }

import reactivemongo.core.actors.{
  ChannelConnected,
  ChannelClosed,
  ChannelDisconnected
}

import reactivemongo.api.SerializationPack
import reactivemongo.api.commands.GetLastError

import reactivemongo.core.errors._
import reactivemongo.core.netty._
import reactivemongo.util.LazyLogger
import BufferAccessors._
import reactivemongo.api.ReadPreference

object `package` {
  @deprecated("Will be removed", "0.12.0")
  class RichBuffer(val buffer: ChannelBuffer) extends AnyVal {
    import scala.collection.mutable.ArrayBuffer

    /** Write a UTF-8 encoded C-Style String. */
    def writeCString(s: String): ChannelBuffer = {
      val bytes = s.getBytes("utf-8")
      buffer writeBytes bytes
      buffer writeByte 0
      buffer
    }

    /** Write a UTF-8 encoded String. */
    def writeString(s: String): ChannelBuffer = {
      val bytes = s.getBytes("utf-8")
      buffer writeInt (bytes.size + 1)
      buffer writeBytes bytes
      buffer writeByte 0
      buffer
    }

    /** Write the contents of the given [[reactivemongo.core.protocol.ChannelBufferWritable]]. */
    def write(writable: ChannelBufferWritable) = writable writeTo buffer

    /** Reads a UTF-8 String. */
    def readString(): String = {
      val bytes = new Array[Byte](buffer.readInt - 1)
      buffer.readBytes(bytes)
      buffer.readByte
      new String(bytes, "UTF-8")
    }

    /**
     * Reads an array of Byte of the given length.
     *
     * @param length Length of the newly created array.
     */
    def readArray(length: Int): Array[Byte] = {
      val bytes = new Array[Byte](length)
      buffer.readBytes(bytes)
      bytes
    }

    /** Reads a UTF-8 C-Style String. */
    def readCString(): String = {
      @annotation.tailrec
      def readCString(array: ArrayBuffer[Byte]): String = {
        val byte = buffer.readByte
        if (byte == 0x00) new String(array.toArray, "UTF-8")
        else readCString(array += byte)
      }

      readCString(new ArrayBuffer[Byte](16))
    }
  }
}

// traits
/**
 * Something that can be written into a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]].
 */
trait ChannelBufferWritable {
  /** Write this instance into the given [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]]. */
  def writeTo: ChannelBuffer => Unit

  /** Size of the content that would be written. */
  def size: Int
}

/**
 * A constructor of T instances from a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]].
 *
 * @tparam T type which instances can be constructed with this.
 */
trait ChannelBufferReadable[T] {
  /** Makes an instance of T from the data from the given buffer. */
  def readFrom(buffer: ChannelBuffer): T

  /** @see readFrom */
  def apply(buffer: ChannelBuffer): T = readFrom(buffer)
}

// concrete classes
/**
 * Header of a Mongo Wire Protocol message.
 *
 * @param messageLength length of this message.
 * @param requestID id of this request (> 0 for request operations, else 0).
 * @param responseTo id of the request that the message including this a response to (> 0 for reply operation, else 0).
 * @param opCode operation code of this message.
 */
case class MessageHeader(
  messageLength: Int,
  requestID: Int,
  responseTo: Int,
  opCode: Int) extends ChannelBufferWritable {
  override val writeTo = writeTupleToBuffer4((messageLength, requestID, responseTo, opCode)) _
  override val size = 4 + 4 + 4 + 4
}

/** Header deserializer from a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]]. */
object MessageHeader extends ChannelBufferReadable[MessageHeader] {
  override def readFrom(buffer: ChannelBuffer) = {
    val messageLength = buffer.readInt
    val requestID = buffer.readInt
    val responseTo = buffer.readInt
    val opCode = buffer.readInt
    MessageHeader(
      messageLength,
      requestID,
      responseTo,
      opCode)
  }
}

/**
 * Request message.
 *
 * @param requestID id of this request, so that the response may be identifiable. Should be strictly positive.
 * @param op request operation.
 * @param documents body of this request, a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]] containing 0, 1, or many documents.
 * @param channelIdHint a hint for sending this request on a particular channel.
 */
case class Request(
  requestID: Int,
  responseTo: Int, // TODO remove, nothing to do here.
  op: RequestOp,
  documents: BufferSequence,
  readPreference: ReadPreference = ReadPreference.primary,
  channelIdHint: Option[Int] = None) extends ChannelBufferWritable {
  private def write(buffer: ChannelBuffer, writable: ChannelBufferWritable) =
    writable writeTo buffer

  override val writeTo = { buffer: ChannelBuffer =>
    write(buffer, header)
    write(buffer, op)
    buffer writeBytes documents.merged
  }

  override def size = 16 + op.size + documents.merged.writerIndex

  /** Header of this request */
  lazy val header = MessageHeader(size, requestID, responseTo, op.code)

  override def toString =
    s"Request($requestID, $responseTo, $op, $readPreference, $channelIdHint)"
}

/**
 * A helper to build write request which result needs to be checked.
 *
 * @param op write operation.
 * @param documents body of this request, a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]] containing 0, 1, or many documents.
 * @param getLastError a `GetLastError` command message.
 */
case class CheckedWriteRequest(
  op: WriteRequestOp,
  documents: BufferSequence,
  getLastError: GetLastError) {
  def apply(): (RequestMaker, RequestMaker) = {
    import reactivemongo.api.BSONSerializationPack
    import reactivemongo.api.commands.Command
    import reactivemongo.api.commands.bson.BSONGetLastErrorImplicits.GetLastErrorWriter
    val gleRequestMaker = Command.requestMaker(BSONSerializationPack).onDatabase(op.db, getLastError, ReadPreference.primary)(GetLastErrorWriter).requestMaker
    RequestMaker(op, documents) -> gleRequestMaker
  }
}

/**
 * A helper to build requests.
 *
 * @param op write operation.
 * @param documents body of this request, a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]] containing 0, 1, or many documents.
 * @param channelIdHint a hint for sending this request on a particular channel.
 */
case class RequestMaker(
  op: RequestOp,
  documents: BufferSequence = BufferSequence.empty,
  readPreference: ReadPreference = ReadPreference.primary,
  channelIdHint: Option[Int] = None) {
  def apply(id: Int): Request = Request(
    id, 0, op, documents, readPreference, channelIdHint)
}

/**
 * @define requestID id of this request, so that the response may be identifiable. Should be strictly positive.
 * @define op request operation.
 * @define documentsA body of this request, an Array containing 0, 1, or many documents.
 * @define documentsC body of this request, a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]] containing 0, 1, or many documents.
 */
object Request {
  /**
   * Create a request.
   *
   * @param requestID $requestID
   * @param op $op
   * @param documents $documentsA
   */
  def apply(requestID: Int, responseTo: Int, op: RequestOp, documents: Array[Byte]): Request = Request(
    requestID,
    responseTo,
    op,
    BufferSequence(
      ChannelBuffers.wrappedBuffer(ByteOrder.LITTLE_ENDIAN, documents)))

  /**
   * Create a request.
   *
   * @param requestID $requestID
   * @param op $op
   * @param documents $documentsA
   */
  def apply(requestID: Int, op: RequestOp, documents: Array[Byte]): Request =
    Request.apply(requestID, 0, op, documents)

  /**
   * Create a request.
   *
   * @param requestID $requestID
   * @param op $op
   */
  def apply(requestID: Int, op: RequestOp): Request =
    Request.apply(requestID, op, new Array[Byte](0))

}

/**
 * A Mongo Wire Protocol Response messages.
 *
 * @param header the header of this response
 * @param reply the reply operation contained in this response
 * @param documents the body of this response, a [[http://static.netty.io/3.5/api/org/jboss/netty/buffer/ChannelBuffer.html ChannelBuffer]] containing 0, 1, or many documents
 * @param info some meta information about this response, see [[reactivemongo.core.protocol.ResponseInfo]]
 */
sealed abstract class Response(
  val header: MessageHeader,
  val reply: Reply,
  val documents: ChannelBuffer,
  val info: ResponseInfo) extends Product4[MessageHeader, Reply, ChannelBuffer, ResponseInfo] with Serializable {
  @inline def _1 = header
  @inline def _2 = reply
  @inline def _3 = documents
  @inline def _4 = info

  def canEqual(that: Any): Boolean = that match {
    case _: Response => true
    case _           => false
  }

  /** If this response is in error, explain this error. */
  lazy val error: Option[DatabaseException] = {
    if (reply.inError) {
      val bson = Response.parse(this)

      if (bson.hasNext) Some(DatabaseException(bson.next))
      else None
    } else None
  }

  private[reactivemongo] def cursorID(id: Long): Response

  private[reactivemongo] def startingFrom(offset: Int): Response

  override def toString = s"Response($header, $reply, $info)"
}

object Response {
  import reactivemongo.api.BSONSerializationPack
  import reactivemongo.bson.BSONDocument
  import reactivemongo.bson.DefaultBSONHandlers.BSONDocumentIdentity

  def apply(
    header: MessageHeader,
    reply: Reply,
    documents: ChannelBuffer,
    info: ResponseInfo): Response = Successful(header, reply, documents, info)

  def parse(response: Response): Iterator[BSONDocument] = parse[BSONSerializationPack.type, BSONDocument](BSONSerializationPack)(response, BSONDocumentIdentity)

  @inline private[reactivemongo] def parse[P <: SerializationPack, T](
    pack: P)(response: Response, reader: pack.Reader[T]): Iterator[T] =
    ReplyDocumentIterator.parse(pack)(response)(reader)

  def unapply(response: Response): Option[(MessageHeader, Reply, ChannelBuffer, ResponseInfo)] = Some((response.header, response.reply, response.documents, response.info))

  // ---

  private[reactivemongo] final case class Successful(
    _header: MessageHeader,
    _reply: Reply,
    _documents: ChannelBuffer,
    _info: ResponseInfo) extends Response(
    _header, _reply, _documents, _info) {

    private[reactivemongo] def cursorID(id: Long): Response =
      copy(_reply = this._reply.copy(cursorID = id))

    private[reactivemongo] def startingFrom(offset: Int): Response =
      copy(_reply = this._reply.copy(startingFrom = offset))
  }

  // For MongoDB 3.2+ response with cursor
  private[reactivemongo] final case class WithCursor(
    _header: MessageHeader,
    _reply: Reply,
    _documents: ChannelBuffer,
    _info: ResponseInfo,
    ns: String,
    private[core]preloaded: Seq[BSONDocument]) extends Response(
    _header, _reply, _documents, _info) {
    private[reactivemongo] def cursorID(id: Long): Response =
      copy(_reply = this._reply.copy(cursorID = id))

    private[reactivemongo] def startingFrom(offset: Int): Response =
      copy(_reply = this._reply.copy(startingFrom = offset))
  }

  private[reactivemongo] final case class CommandError(
    _header: MessageHeader,
    _reply: Reply,
    _info: ResponseInfo,
    private[reactivemongo]cause: DatabaseException) extends Response(_header, _reply, ChannelBuffers.EMPTY_BUFFER, _info) {
    override lazy val error: Option[DatabaseException] = Some(cause)

    private[reactivemongo] def cursorID(id: Long): Response =
      copy(_reply = this._reply.copy(cursorID = id))

    private[reactivemongo] def startingFrom(offset: Int): Response =
      copy(_reply = this._reply.copy(startingFrom = offset))
  }
}

/**
 * Response meta information.
 *
 * @param channelId the id of the channel that carried this response.
 */
case class ResponseInfo(channelId: Int)

// protocol handlers for netty.
private[reactivemongo] class RequestEncoder extends OneToOneEncoder {
  import RequestEncoder._
  def encode(ctx: ChannelHandlerContext, channel: Channel, obj: Object) =
    obj match {
      case message: Request => {
        val buffer: ChannelBuffer = ChannelBuffers.buffer(ByteOrder.LITTLE_ENDIAN, message.size) //ChannelBuffers.dynamicBuffer(ByteOrder.LITTLE_ENDIAN, 1000)
        message writeTo buffer
        buffer
      }

      case _ => {
        logger.error(s"Weird... do not know how to encode this object: $obj")
        obj
      }
    }

}

object ReplyDocumentIterator {
  private[reactivemongo] def parse[P <: SerializationPack, A](pack: P)(response: Response)(implicit reader: pack.Reader[A]): Iterator[A] = response match {
    case Response.CommandError(_, _, _, cause) => new Iterator[A] {
      val hasNext = false
      @inline def next: A = throw cause
      //throw ReplyDocumentIteratorExhaustedException(cause)
    }

    case Response.WithCursor(_, _, _, _, _, preloaded) => {
      val buf = response.documents

      if (buf.readableBytes == 0) {
        Iterator.empty
      } else {
        try {
          buf.skipBytes(buf.getInt(buf.readerIndex))

          def docs = apply[P, A](pack)(response.reply, buf)

          val firstBatch = preloaded.iterator.map { bson =>
            pack.deserialize(pack.document(bson), reader)
          }

          firstBatch ++ docs
        } catch {
          case cause: Exception => new Iterator[A] {
            val hasNext = false
            @inline def next: A = throw cause
            //throw ReplyDocumentIteratorExhaustedException(cause)
          }
        }
      }
    }

    case _ => apply[P, A](pack)(response.reply, response.documents)
  }

  def apply[P <: SerializationPack, A](pack: P)(reply: Reply, buffer: ChannelBuffer)(implicit reader: pack.Reader[A]): Iterator[A] = new Iterator[A] {
    override val isTraversableAgain = false // TODO: Add test
    override def hasNext = buffer.readable

    override def next = try {
      val rb = ChannelBufferReadableBuffer(
        buffer readBytes buffer.getInt(buffer.readerIndex))

      pack.readAndDeserialize(rb, reader)
    } catch {
      case e: IndexOutOfBoundsException =>
        /*
         * If this happens, the buffer is exhausted, and there is probably a bug.
         * It may happen if an enumerator relying on it is concurrently applied to many iteratees – which should not be done!
         */
        throw ReplyDocumentIteratorExhaustedException(e)
    }
  }
}

case class ReplyDocumentIteratorExhaustedException(
  val cause: Exception) extends Exception(cause)

private[reactivemongo] object RequestEncoder {
  val logger = LazyLogger("reactivemongo.core.protocol.RequestEncoder")
}

private[reactivemongo] class ResponseFrameDecoder extends FrameDecoder {
  override def decode(context: ChannelHandlerContext, channel: Channel, buffer: ChannelBuffer) = {
    val readableBytes = buffer.readableBytes

    if (readableBytes < 4) null
    else {
      buffer.markReaderIndex()

      val length = buffer.readInt

      buffer.resetReaderIndex

      if (length <= readableBytes && length > 0) {
        buffer.readBytes(length)
      } else null
    }
  }
}

private[reactivemongo] class ResponseDecoder extends OneToOneDecoder {
  def decode(ctx: ChannelHandlerContext, channel: Channel, obj: Object): Response = {
    val buffer = obj.asInstanceOf[ChannelBuffer]
    val header = MessageHeader(buffer)
    val reply = Reply(buffer)
    val info = ResponseInfo(channel.getId)

    if (reply.cursorID == 0 && reply.numberReturned > 0) {
      buffer.markReaderIndex()

      document(buffer) match {
        case Failure(cause) =>
          Response.CommandError(header, reply, info, DatabaseException(cause))

        case Success(doc) => {
          val ok = doc.getAs[BSONBooleanLike]("ok")
          def failed = {
            val r = {
              if (reply.inError) reply
              else reply.copy(flags = reply.flags | 0x02)
            }

            Response.CommandError(header, r, info, DatabaseException(doc))
          }

          doc.getAs[BSONDocument]("cursor") match {
            case Some(cursor) if ok.exists(_.toBoolean) => {
              val ry = for {
                id <- cursor.getAs[BSONNumberLike]("id").map(_.toLong)
                ns <- cursor.getAs[String]("ns")
                batch <- cursor.getAs[Seq[BSONDocument]]("firstBatch").orElse(
                  cursor.getAs[Seq[BSONDocument]]("nextBatch"))

              } yield (ns, batch, reply.copy(
                cursorID = id,
                numberReturned = batch.size))

              buffer.resetReaderIndex()

              ry.fold(Response(header, reply, buffer, info)) {
                case (ns, batch, r) => Response.WithCursor(
                  header, r, buffer, info, ns, batch)
              }
            }

            case Some(_) => failed

            case _ => {
              buffer.resetReaderIndex()

              if (ok.forall(_.toBoolean)) {
                Response(header, reply, buffer, info)
              } else { // !ok
                failed
              }
            }
          }
        }
      }
    } else {
      Response(header, reply, buffer, info)
    }
  }

  // ---

  @inline private def document(buf: ChannelBuffer) = Try[BSONDocument] {
    val docBuf = ChannelBufferReadableBuffer(
      buf readBytes buf.getInt(buf.readerIndex))

    reactivemongo.api.BSONSerializationPack.readFromBuffer(docBuf)
  }
}

private[reactivemongo] class MongoHandler(
  supervisor: String, connection: String, receiver: ActorRef) extends IdleStateAwareChannelHandler {

  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val response = e.getMessage.asInstanceOf[Response]
    log(e, s"messageReceived: $response will be send to $receiver")
    receiver ! response
    super.messageReceived(ctx, e)
  }

  override def writeComplete(ctx: ChannelHandlerContext, e: WriteCompletionEvent) {
    log(e, "A write is complete!")
    super.writeComplete(ctx, e)
  }

  override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
    log(e, "A write is requested!")
    super.writeRequested(ctx, e)
  }

  override def channelConnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    log(e, "Channel is connected")
    receiver ! ChannelConnected(e.getChannel.getId)
    super.channelConnected(ctx, e)
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    log(e, "Channel is disconnected")
    receiver ! ChannelDisconnected(e.getChannel.getId)
    super.channelDisconnected(ctx, e)
  }

  override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
    if (e.getChannel.getRemoteAddress != null) log(e, "Channel is closed")

    receiver ! ChannelClosed(e.getChannel.getId)

    super.channelClosed(ctx, e)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: shaded.netty.channel.ExceptionEvent) = log(e, "Channel error", e.getCause)

  override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) = {
    val now = System.currentTimeMillis()
    val last = e.getLastActivityTimeMillis

    log(e, s"Channel has been inactive for ${now - last} (last = $last)")
    e.getChannel.close()

    super.channelIdle(ctx, e)
  }

  @inline def log(e: ChannelEvent, s: String) = MongoHandler.
    logger.trace(s"[$supervisor/$connection] @ ${e.getChannel}] $s")

  @inline def log(e: ChannelEvent, s: String, cause: Throwable) = MongoHandler.
    logger.trace(s"[$supervisor/$connection] @ ${e.getChannel}] $s", cause)
}

private[reactivemongo] object MongoHandler {
  private val logger = LazyLogger("reactivemongo.core.protocol.MongoHandler")
}
