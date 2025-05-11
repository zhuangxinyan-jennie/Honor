import chisel3._
import chisel3.util._
import scala.collection.immutable.Seq
import chisel3.stage._

class BTB(
  t0Len: Int,    // T0表大小
  tnLen: Int,    // T1-T4表大小
  predWidth: Int,   // 预测值位宽
  tagWidth: Int,    // 标签位宽
  uWidth: Int,      // 有用性计数器位宽
  ghrWidth: Int,  // 全局历史寄存器位宽
  btbNum: Int    // BTB表大小
) extends Module {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rst = Input(Bool())
    val enaD = Input(Bool())
    val instrD = Input(UInt(32.W))
    val pcD = Input(UInt(32.W))
    val pcPlus4D = Input(UInt(32.W))
    val branchD = Output(Bool())
    val predTakeD = Output(Bool())
    val branchTargetD = Output(UInt(32.W))
    val pcE = Input(UInt(32.W))
    val pcETarget = Input(UInt(32.W))
    val branchE = Input(Bool())
    val actualTakeE = Input(Bool())
  })

  // 计算受控参数
  val l4 = ghrWidth
  val l3 = (ghrWidth * 0.75).toInt
  val l2 = (ghrWidth * 0.5).toInt
  val l1 = (ghrWidth * 0.25).toInt
  val tnLenLg2 = log2Ceil(tnLen)
  val btbNumLg2 = log2Ceil(btbNum)

  // 存储声明
  val t0Pred = Reg(Vec(t0Len, UInt(predWidth.W)))
  val t1Pred = Reg(Vec(tnLen, UInt(predWidth.W)))
  val t1Tag = Reg(Vec(tnLen, UInt(tagWidth.W)))
  val t1U = Reg(Vec(tnLen, UInt(uWidth.W)))
  val t2Pred = Reg(Vec(tnLen, UInt(predWidth.W)))
  val t2Tag = Reg(Vec(tnLen, UInt(tagWidth.W)))
  val t2U = Reg(Vec(tnLen, UInt(uWidth.W)))
  val t3Pred = Reg(Vec(tnLen, UInt(predWidth.W)))
  val t3Tag = Reg(Vec(tnLen, UInt(tagWidth.W)))
  val t3U = Reg(Vec(tnLen, UInt(uWidth.W)))
  val t4Pred = Reg(Vec(tnLen, UInt(predWidth.W)))
  val t4Tag = Reg(Vec(tnLen, UInt(tagWidth.W)))
  val t4U = Reg(Vec(tnLen, UInt(uWidth.W)))
  val btbPc = Reg(Vec(btbNum, UInt(32.W)))
  val ghr = Reg(UInt(ghrWidth.W))
  val lastPred = Reg(Bool())

  // 组合逻辑信号
  val hashT1 = Wire(UInt(tnLenLg2.W))
  val hashT2 = Wire(UInt(tnLenLg2.W))
  val hashT3 = Wire(UInt(tnLenLg2.W))
  val hashT4 = Wire(UInt(tnLenLg2.W))
  val hashT1E = Wire(UInt(tnLenLg2.W))
  val hashT2E = Wire(UInt(tnLenLg2.W))
  val hashT3E = Wire(UInt(tnLenLg2.W))
  val hashT4E = Wire(UInt(tnLenLg2.W))

  // 定义压缩函数
  def compress(input: UInt, targetWidth: Int, Ln: Int): UInt = {
    val compressed = Wire(UInt(targetWidth.W))
    val step = (Ln + targetWidth - 1) / targetWidth  // 向上取整计算步长
    var temp = 0.U(targetWidth.W)
    
    // 使用异或电路压缩pc
    if (input.getWidth == 32) {
      // 32位pc压缩为8位
      val part1 = input(7, 0) ^ input(15, 8)
      val part2 = input(23, 16) ^ input(31, 24)
      temp = part1 ^ part2
    } else {
      // ghr压缩，使用重叠采样
      for (i <- 0 until targetWidth) {
        val start = i * step
        val end = start + targetWidth - 1
        // 确保end不超过Ln-1
        val safeEnd = if (end >= Ln) Ln-1 else end
        // 确保start不超过safeEnd
        val safeStart = if (start > safeEnd) safeEnd else start
        // 确保safeEnd不超过输入信号的最大位宽
        val maxEnd = if (safeEnd >= input.getWidth) input.getWidth-1 else safeEnd
        // 使用Int类型的参数进行位选择
        val part = input(maxEnd, safeStart)
        // 使用异或压缩
        var xorResult = 0.U(1.W)
        for (j <- 0 until part.getWidth) {
          xorResult = xorResult ^ part(j)
        }
        temp = temp | (xorResult << i.U)
      }
    }
    compressed := temp
    compressed
  }

  // 计算hashT
  hashT1 := compress(io.pcD, tnLenLg2, l1) ^ compress(ghr, tnLenLg2, l1)
  hashT2 := compress(io.pcD, tnLenLg2, l2) ^ compress(ghr, tnLenLg2, l2)
  hashT3 := compress(io.pcD, tnLenLg2, l3) ^ compress(ghr, tnLenLg2, l3)
  hashT4 := compress(io.pcD, tnLenLg2, l4) ^ compress(ghr, tnLenLg2, l4)

  hashT1E := compress(io.pcE, tnLenLg2, l1) ^ compress(ghr, tnLenLg2, l1)
  hashT2E := compress(io.pcE, tnLenLg2, l2) ^ compress(ghr, tnLenLg2, l2)
  hashT3E := compress(io.pcE, tnLenLg2, l3) ^ compress(ghr, tnLenLg2, l3)
  hashT4E := compress(io.pcE, tnLenLg2, l4) ^ compress(ghr, tnLenLg2, l4)

  // 计算tagT
  val tagT1 = compress(io.pcD >> 2.U, tagWidth, l1) ^ compress(ghr, tagWidth, l1)
  val tagT2 = compress(io.pcD >> 2.U, tagWidth, l2) ^ compress(ghr, tagWidth, l2)
  val tagT3 = compress(io.pcD >> 2.U, tagWidth, l3) ^ compress(ghr, tagWidth, l3)
  val tagT4 = compress(io.pcD >> 2.U, tagWidth, l4) ^ compress(ghr, tagWidth, l4)

  val tagT1E = compress(io.pcE >> 2.U, tagWidth, l1) ^ compress(ghr, tagWidth, l1)
  val tagT2E = compress(io.pcE >> 2.U, tagWidth, l2) ^ compress(ghr, tagWidth, l2)
  val tagT3E = compress(io.pcE >> 2.U, tagWidth, l3) ^ compress(ghr, tagWidth, l3)
  val tagT4E = compress(io.pcE >> 2.U, tagWidth, l4) ^ compress(ghr, tagWidth, l4)

  // 命中判断
  val hitT4 = t4Tag(hashT4) === tagT4
  val hitT3 = t3Tag(hashT3) === tagT3
  val hitT2 = t2Tag(hashT2) === tagT2
  val hitT1 = t1Tag(hashT1) === tagT1

  val hitT4E = t4Tag(hashT4E) === tagT4E
  val hitT3E = t3Tag(hashT3E) === tagT3E
  val hitT2E = t2Tag(hashT2E) === tagT2E
  val hitT1E = t1Tag(hashT1E) === tagT1E

  // 预测值读取
  val predT0 = t0Pred(io.pcD(tnLenLg2+1, 2))
  val predT1 = t1Pred(hashT1)
  val predT2 = t2Pred(hashT2)
  val predT3 = t3Pred(hashT3)
  val predT4 = t4Pred(hashT4)

  // 优先级选择器
  val providerE = MuxCase(0.U(4.W), Seq(
    hitT4E -> 4.U(4.W),
    hitT3E -> 3.U(4.W),
    hitT2E -> 2.U(4.W),
    hitT1E -> 1.U(4.W)
  ))

  // 分支检测
  val bType = io.instrD(6, 0) === "b1100011".U
  val jType = io.instrD(6, 0) === "b1101111".U || io.instrD(6, 0) === "b1100111".U
  io.branchD := bType || jType

  // 最终预测结果
  val use = (io.actualTakeE && lastPred) || (!io.actualTakeE && !lastPred)
  io.predTakeD := MuxCase(predT0(predWidth-1), Seq(
    jType -> true.B,
    hitT4 -> predT4(predWidth-1),
    hitT3 -> predT3(predWidth-1),
    hitT2 -> predT2(predWidth-1),
    hitT1 -> predT1(predWidth-1)
  ))

  // BTB目标地址
  io.branchTargetD := Mux(io.predTakeD, btbPc(io.pcD(btbNumLg2-1, 0)), io.pcPlus4D)

  // 时序更新逻辑
  when(io.rst) {
    // 初始化所有表
    for(i <- 0 until t0Len) {
      t0Pred(i) := Cat(0.U(1.W), Fill(predWidth-1, 1.U(1.W)))
    }
    
    for(i <- 0 until tnLen) {
      t1Pred(i) := Cat(0.U(1.W), Fill(predWidth-1, 1.U(1.W)))
      t1Tag(i) := 0.U
      t1U(i) := 0.U
      t2Pred(i) := Cat(0.U(1.W), Fill(predWidth-1, 1.U(1.W)))
      t2Tag(i) := 0.U
      t2U(i) := 0.U
      t3Pred(i) := Cat(0.U(1.W), Fill(predWidth-1, 1.U(1.W)))
      t3Tag(i) := 0.U
      t3U(i) := 0.U
      t4Pred(i) := Cat(0.U(1.W), Fill(predWidth-1, 1.U(1.W)))
      t4Tag(i) := 0.U
      t4U(i) := 0.U
    }
    
    // 初始化BTB
    for(i <- 0 until btbNum) {
      btbPc(i) := "h80000000".U(32.W)
    }
    
    ghr := 0.U
    lastPred := false.B
  }.otherwise {
    when(io.branchD && io.enaD) {
      lastPred := io.predTakeD
    }

    // 更新GHR
    when(io.branchE) {
      ghr := Cat(ghr(ghrWidth-2, 0), io.actualTakeE)
    }
    
    // 更新BTB
    when(io.branchE && io.actualTakeE) {
      btbPc(io.pcE(btbNumLg2-1, 0)) := io.pcETarget
    }
    
    // 更新预测表
    when(io.branchE) {
      when(providerE === 0.U) {
        // T0更新
        t0Pred(io.pcE(tnLenLg2+1, 2)) := Mux(io.actualTakeE,
          t0Pred(io.pcE(tnLenLg2+1, 2)) + 1.U,
          t0Pred(io.pcE(tnLenLg2+1, 2)) - 1.U
        )
      }.elsewhen(providerE === 1.U) {
        // T1更新
        t1Pred(hashT1E) := Mux(io.actualTakeE,
          Mux(t1Pred(hashT1E) === Fill(predWidth, 1.U(1.W)), t1Pred(hashT1E), t1Pred(hashT1E) + 1.U),
          Mux(t1Pred(hashT1E) === 0.U, t1Pred(hashT1E), t1Pred(hashT1E) - 1.U)
        )

        t1U(hashT1E) := Mux(use,
          Mux(t1U(hashT1E) === Fill(uWidth, 1.U(1.W)), t1U(hashT1E), t1U(hashT1E) + 1.U),
          Mux(t1U(hashT1E) === 0.U, t1U(hashT1E), t1U(hashT1E) - 1.U)
        )

        when(!use) {
          when(t2U(hashT2E) === 0.U) {
            t2Pred(hashT2E) := Cat(io.actualTakeE, Fill(predWidth-1, !io.actualTakeE))
            t2U(hashT2E) := 0.U
            t2Tag(hashT2E) := tagT2E
          }.elsewhen(t3U(hashT3E) === 0.U) {
            t3Pred(hashT3E) := Cat(io.actualTakeE, Fill(predWidth-1, !io.actualTakeE))
            t3U(hashT3E) := 0.U
            t3Tag(hashT3E) := tagT3E
          }.elsewhen(t4U(hashT4E) === 0.U) {
            t4Pred(hashT4E) := Cat(io.actualTakeE, Fill(predWidth-1, !io.actualTakeE))
            t4U(hashT4E) := 0.U
            t4Tag(hashT4E) := tagT4E
          }.otherwise {
            t2U(hashT2E) := t2U(hashT2E) - 1.U
            t3U(hashT3E) := t3U(hashT3E) - 1.U
            t4U(hashT4E) := t4U(hashT4E) - 1.U
          }
        }
      }.elsewhen(providerE === 2.U) {
        // T2更新
        t2Pred(hashT2E) := Mux(io.actualTakeE,
          Mux(t2Pred(hashT2E) === Fill(predWidth, 1.U(1.W)), t2Pred(hashT2E), t2Pred(hashT2E) + 1.U),
          Mux(t2Pred(hashT2E) === 0.U, t2Pred(hashT2E), t2Pred(hashT2E) - 1.U)
        )

        t2U(hashT2E) := Mux(use,
          Mux(t2U(hashT2E) === Fill(uWidth, 1.U(1.W)), t2U(hashT2E), t2U(hashT2E) + 1.U),
          Mux(t2U(hashT2E) === 0.U, t2U(hashT2E), t2U(hashT2E) - 1.U)
        )

        when(!use) {
          when(t3U(hashT3E) === 0.U) {
            t3Pred(hashT3E) := Cat(io.actualTakeE, Fill(predWidth-1, !io.actualTakeE))
            t3U(hashT3E) := 0.U
            t3Tag(hashT3E) := tagT3E
          }.elsewhen(t4U(hashT4E) === 0.U) {
            t4Pred(hashT4E) := Cat(io.actualTakeE, Fill(predWidth-1, !io.actualTakeE))
            t4U(hashT4E) := 0.U
            t4Tag(hashT4E) := tagT4E
          }.otherwise {
            t3U(hashT3E) := t3U(hashT3E) - 1.U
            t4U(hashT4E) := t4U(hashT4E) - 1.U
          }
        }
      }.elsewhen(providerE === 3.U) {
        // T3更新
        t3Pred(hashT3E) := Mux(io.actualTakeE,
          Mux(t3Pred(hashT3E) === Fill(predWidth, 1.U(1.W)), t3Pred(hashT3E), t3Pred(hashT3E) + 1.U),
          Mux(t3Pred(hashT3E) === 0.U, t3Pred(hashT3E), t3Pred(hashT3E) - 1.U)
        )

        t3U(hashT3E) := Mux(use,
          Mux(t3U(hashT3E) === Fill(uWidth, 1.U(1.W)), t3U(hashT3E), t3U(hashT3E) + 1.U),
          Mux(t3U(hashT3E) === 0.U, t3U(hashT3E), t3U(hashT3E) - 1.U)
        )

        when(!use) {
          when(t4U(hashT4E) === 0.U) {
            t4Pred(hashT4E) := Cat(io.actualTakeE, Fill(predWidth-1, !io.actualTakeE))
            t4U(hashT4E) := 0.U
            t4Tag(hashT4E) := tagT4E
          }.otherwise {
            t4U(hashT4E) := t4U(hashT4E) - 1.U
          }
        }
      }.elsewhen(providerE === 4.U) {
        // T4更新
        t4Pred(hashT4E) := Mux(io.actualTakeE,
          Mux(t4Pred(hashT4E) === Fill(predWidth, 1.U(1.W)), t4Pred(hashT4E), t4Pred(hashT4E) + 1.U),
          Mux(t4Pred(hashT4E) === 0.U, t4Pred(hashT4E), t4Pred(hashT4E) - 1.U)
        )

        t4U(hashT4E) := Mux(use,
          Mux(t4U(hashT4E) === Fill(uWidth, 1.U(1.W)), t4U(hashT4E), t4U(hashT4E) + 1.U),
          Mux(t4U(hashT4E) === 0.U, t4U(hashT4E), t4U(hashT4E) - 1.U)
        )
      }
    }
  }
}

object MyModule extends App {
  // 使用Chisel 3.5兼容的方式生成Verilog
  (new chisel3.stage.ChiselStage).execute(
    Array("-X", "verilog"),
    Seq(
      chisel3.stage.ChiselGeneratorAnnotation(() => new BTB(2048, 1024, 3, 8, 2, 100, 1024))
    )
  )
} 
