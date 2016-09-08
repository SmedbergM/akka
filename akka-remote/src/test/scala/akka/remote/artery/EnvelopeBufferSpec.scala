/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import java.nio.{ ByteBuffer, ByteOrder }

import akka.actor._
import akka.remote.artery.compress.{ CompressionTable, CompressionTestUtils, InboundCompressions }
import akka.testkit.AkkaSpec
import akka.util.{ ByteString, OptionVal }

class EnvelopeBufferSpec extends AkkaSpec {
  import CompressionTestUtils._

  object TestCompressor extends InboundCompressions {
    val refToIdx: Map[ActorRef, Int] = Map(
      minimalRef("compressable0") → 0,
      minimalRef("compressable1") → 1,
      minimalRef("reallylongcompressablestring") → 2)
    val idxToRef: Map[Int, ActorRef] = refToIdx.map(_.swap)

    val serializerToIdx = Map(
      "serializer0" → 0,
      "serializer1" → 1)
    val idxToSer = serializerToIdx.map(_.swap)

    val manifestToIdx = Map(
      "manifest0" → 0,
      "manifest1" → 1)
    val idxToManifest = manifestToIdx.map(_.swap)

    val outboundActorRefTable: CompressionTable[ActorRef] =
      CompressionTable(version = 0xCAFE, refToIdx)

    val outboundClassManifestTable: CompressionTable[String] =
      CompressionTable(version = 0xBABE, manifestToIdx)

    override def hitActorRef(originUid: Long, remote: Address, ref: ActorRef, n: Int): Unit = ()
    override def decompressActorRef(originUid: Long, tableVersion: Int, idx: Int): OptionVal[ActorRef] = OptionVal(idxToRef(idx))
    override def confirmActorRefCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit = ()

    override def hitClassManifest(originUid: Long, remote: Address, manifest: String, n: Int): Unit = ()
    override def decompressClassManifest(originUid: Long, tableVersion: Int, idx: Int): OptionVal[String] = OptionVal(idxToManifest(idx))
    override def confirmClassManifestCompressionAdvertisement(originUid: Long, tableVersion: Int): Unit = ()
  }

  "EnvelopeBuffer" must {
    val headerIn = HeaderBuilder.bothWays(TestCompressor, TestCompressor.outboundActorRefTable, TestCompressor.outboundClassManifestTable)
    val headerOut = HeaderBuilder.bothWays(TestCompressor, TestCompressor.outboundActorRefTable, TestCompressor.outboundClassManifestTable)

    val byteBuffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
    val envelope = new EnvelopeBuffer(byteBuffer)

    val originUid = 1L

    "be able to encode and decode headers with compressed literals" in {
      headerIn setVersion 1
      headerIn setUid 42
      headerIn setSerializer 4
      headerIn setRecipientActorRef minimalRef("compressable1")
      headerIn setSenderActorRef minimalRef("compressable0")

      headerIn setManifest "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(EnvelopeBuffer.LiteralsSectionOffset) // Fully compressed header

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.inboundActorRefCompressionTableVersion should ===(0xCAFE)
      headerOut.inboundClassManifestCompressionTableVersion should ===(0xBABE)
      headerOut.serializer should ===(4)
      headerOut.senderActorRef(originUid).get.path.toSerializationFormat should ===("akka://EnvelopeBufferSpec/compressable0")
      headerOut.senderActorRefPath should ===(OptionVal.None)
      headerOut.recipientActorRef(originUid).get.path.toSerializationFormat should ===("akka://EnvelopeBufferSpec/compressable1")
      headerOut.recipientActorRefPath should ===(OptionVal.None)
      headerOut.manifest(originUid) should ===("manifest1")
    }

    "be able to encode and decode headers with uncompressed literals" in {
      headerIn setVersion 1
      headerIn setUid 42
      headerIn setSerializer 4
      headerIn setSenderActorRef minimalRef("uncompressable0")
      headerIn setRecipientActorRef minimalRef("uncompressable11")
      headerIn setManifest "uncompressable3333"

      val expectedHeaderLength =
        EnvelopeBuffer.LiteralsSectionOffset + // Constant header part
          2 + headerIn.senderActorRefPath.get.length + // Length field + literal
          2 + headerIn.recipientActorRefPath.get.length + // Length field + literal
          2 + headerIn.manifest(originUid).length // Length field + literal

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(expectedHeaderLength)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRefPath should ===(OptionVal.Some("akka://EnvelopeBufferSpec/uncompressable0"))
      headerOut.senderActorRef(originUid) should ===(OptionVal.None)
      headerOut.recipientActorRefPath should ===(OptionVal.Some("akka://EnvelopeBufferSpec/uncompressable11"))
      headerOut.recipientActorRef(originUid) should ===(OptionVal.None)
      headerOut.manifest(originUid) should ===("uncompressable3333")
    }

    "be able to encode and decode headers with mixed literals" in {
      headerIn setVersion 1
      headerIn setUid 42
      headerIn setSerializer 4
      headerIn setSenderActorRef minimalRef("reallylongcompressablestring")
      headerIn setRecipientActorRef minimalRef("uncompressable1")
      headerIn setManifest "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.recipientActorRefPath.get.length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRef(originUid).get.path.toSerializationFormat should ===("akka://EnvelopeBufferSpec/reallylongcompressablestring")
      headerOut.senderActorRefPath should ===(OptionVal.None)
      headerOut.recipientActorRefPath should ===(OptionVal.Some("akka://EnvelopeBufferSpec/uncompressable1"))
      headerOut.recipientActorRef(originUid) should ===(OptionVal.None)
      headerOut.manifest(originUid) should ===("manifest1")

      headerIn setVersion 3
      headerIn setUid Long.MinValue
      headerIn setSerializer -1
      headerIn setSenderActorRef minimalRef("uncompressable0")
      headerIn setRecipientActorRef minimalRef("reallylongcompressablestring")
      headerIn setManifest "longlonglongliteralmanifest"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.position() should ===(
        EnvelopeBuffer.LiteralsSectionOffset +
          2 + headerIn.senderActorRefPath.get.length +
          2 + headerIn.manifest(originUid).length)

      envelope.byteBuffer.flip()
      envelope.parseHeader(headerOut)

      headerOut.version should ===(3)
      headerOut.uid should ===(Long.MinValue)
      headerOut.serializer should ===(-1)
      headerOut.senderActorRefPath should ===(OptionVal.Some("akka://EnvelopeBufferSpec/uncompressable0"))
      headerOut.senderActorRef(originUid) should ===(OptionVal.None)
      headerOut.recipientActorRef(originUid).get.path.toSerializationFormat should ===("akka://EnvelopeBufferSpec/reallylongcompressablestring")
      headerOut.recipientActorRefPath should ===(OptionVal.None)
      headerOut.manifest(originUid) should ===("longlonglongliteralmanifest")
    }

    "be able to encode and decode headers with mixed literals and payload" in {
      val payload = ByteString("Hello Artery!")

      headerIn setVersion 1
      headerIn setUid 42
      headerIn setSerializer 4
      headerIn setSenderActorRef minimalRef("reallylongcompressablestring")
      headerIn setRecipientActorRef minimalRef("uncompressable1")
      headerIn setManifest "manifest1"

      envelope.writeHeader(headerIn)
      envelope.byteBuffer.put(payload.toByteBuffer)
      envelope.byteBuffer.flip()

      envelope.parseHeader(headerOut)

      headerOut.version should ===(1)
      headerOut.uid should ===(42)
      headerOut.serializer should ===(4)
      headerOut.senderActorRef(originUid).get.path.toSerializationFormat should ===("akka://EnvelopeBufferSpec/reallylongcompressablestring")
      headerOut.senderActorRefPath should ===(OptionVal.None)
      headerOut.recipientActorRefPath should ===(OptionVal.Some("akka://EnvelopeBufferSpec/uncompressable1"))
      headerOut.recipientActorRef(originUid) should ===(OptionVal.None)
      headerOut.manifest(originUid) should ===("manifest1")

      ByteString.fromByteBuffer(envelope.byteBuffer) should ===(payload)
    }

  }

}