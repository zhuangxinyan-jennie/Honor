import chisel3._
import chisel3.util._

class BTB extends Module with RequireAsyncReset{
  val io = IO(new Bundle {
    val enaD          = Input(Bool())
    val branchE       = Input(Bool())
    val actualTakeE   = Input(Bool())
    val instrD        = Input(UInt(32.W))
    val pcD           = Input(UInt(32.W))
    val pcE           = Input(UInt(32.W))
    val pcETarget     = Input(UInt(32.W))
    val predTakeD     = Output(Bool())
    val branchTargetD = Output(UInt(32.W))
  })

  // Combinational jump check
  val is_jmp = (io.instrD(6,0) === "b1100111".U) ||
               (io.instrD(6,0) === "b1100011".U) ||
               (io.instrD(6,0) === "b1101111".U)

  // Global History Registers
  val GHR       = RegInit(0.U(64.W))
  val GHR_fifo  = RegInit(0.U(64.W))

  // Predictor tables - using RegInit with Vec for proper reset behavior
  val T0_tage = RegInit(VecInit(Seq.fill(256)(0.U(3.W))))
  val T1_tage = RegInit(VecInit(Seq.fill(256)(0.U(9.W))))
  val T2_tage = RegInit(VecInit(Seq.fill(256)(0.U(9.W))))
  val T3_tage = RegInit(VecInit(Seq.fill(256)(0.U(9.W))))
  val T4_tage = RegInit(VecInit(Seq.fill(256)(0.U(9.W))))
  val BHT     = RegInit(VecInit(Seq.fill(128)(0.U(32.W))))

  // Hashes and tags for D stage
  val hash_T0   = io.pcD(7,0)
  val T0_result = T0_tage(hash_T0)

  val hash_T1    = io.pcD(31,24) ^ io.pcD(23,16) ^ io.pcD(15,8) ^ io.pcD(7,0) ^ GHR(7,0)
  val tag_sel_T1 = hash_T1(7,4) ^ hash_T1(3,0)
  val T1_result  = T1_tage(hash_T1)

  val hash_T2    = io.pcD(31,24) ^ io.pcD(23,16) ^ io.pcD(15,8) ^ io.pcD(7,0) ^ GHR(7,0) ^ GHR(15,8)
  val tag_sel_T2 = hash_T2(7,4) ^ hash_T2(3,0)
  val T2_result  = T2_tage(hash_T2)

  val hash_T3    = io.pcD(31,24) ^ io.pcD(23,16) ^ io.pcD(15,8) ^ io.pcD(7,0) ^ GHR(7,0) ^ GHR(15,8) ^ GHR(23,16) ^ GHR(31,24)
  val tag_sel_T3 = hash_T3(7,4) ^ hash_T3(3,0)
  val T3_result  = T3_tage(hash_T3)

  val hash_T4    = io.pcD(31,24) ^ io.pcD(23,16) ^ io.pcD(15,8) ^ io.pcD(7,0) ^ GHR(7,0) ^ GHR(15,8) ^ GHR(23,16) ^ GHR(31,24) ^ GHR(39,32) ^ GHR(47,40) ^ GHR(55,48) ^ GHR(63,56)
  val tag_sel_T4 = hash_T4(7,4) ^ hash_T4(3,0)
  val T4_result  = T4_tage(hash_T4)

  // Prediction output
  io.predTakeD := Mux(is_jmp && io.enaD,
    Mux(T4_result(5,2) === tag_sel_T4, T4_result(8),
    Mux(T3_result(5,2) === tag_sel_T3, T3_result(8),
    Mux(T2_result(5,2) === tag_sel_T2, T2_result(8),
    Mux(T1_result(5,2) === tag_sel_T1, T1_result(8), T0_result(2))))),
    false.B)

  // Branch target
  io.branchTargetD := BHT(io.pcD(6,0))

  // Hashes and tags for E stage
  val T0_result_E = T0_tage(io.pcE(7,0))

  val hash_T1_E    = io.pcE(31,24) ^ io.pcE(23,16) ^ io.pcE(15,8) ^ io.pcE(7,0) ^ GHR_fifo(7,0)
  val tag_sel_T1_E = hash_T1_E(7,4) ^ hash_T1_E(3,0)
  val T1_result_E  = T1_tage(hash_T1_E)

  val hash_T2_E    = io.pcE(31,24) ^ io.pcE(23,16) ^ io.pcE(15,8) ^ io.pcE(7,0) ^ GHR_fifo(7,0) ^ GHR_fifo(15,8)
  val tag_sel_T2_E = hash_T2_E(7,4) ^ hash_T2_E(3,0)
  val T2_result_E  = T2_tage(hash_T2_E)

  val hash_T3_E    = io.pcE(31,24) ^ io.pcE(23,16) ^ io.pcE(15,8) ^ io.pcE(7,0) ^ GHR_fifo(7,0) ^ GHR_fifo(15,8) ^ GHR_fifo(23,16) ^ GHR_fifo(31,24)
  val tag_sel_T3_E = hash_T3_E(7,4) ^ hash_T3_E(3,0)
  val T3_result_E  = T3_tage(hash_T3_E)

  val hash_T4_E    = io.pcE(31,24) ^ io.pcE(23,16) ^ io.pcE(15,8) ^ io.pcE(7,0) ^
                   GHR_fifo(7,0) ^ GHR_fifo(15,8) ^ GHR_fifo(23,16) ^ GHR_fifo(31,24) ^
                   GHR_fifo(39,32) ^ GHR_fifo(47,40) ^ GHR_fifo(55,48) ^ GHR_fifo(63,56)
  val tag_sel_T4_E = hash_T4_E(7,4) ^ hash_T4_E(3,0)
  val T4_result_E  = T4_tage(hash_T4_E)

  // Flags and alt tage
  val is_T4 = T4_result_E(5,2) === tag_sel_T4_E
  val is_T3 = T3_result_E(5,2) === tag_sel_T3_E
  val is_T2 = T2_result_E(5,2) === tag_sel_T2_E
  val is_T1 = T1_result_E(5,2) === tag_sel_T1_E

  val alt_tage = Mux(is_T4 && is_T3, T3_result_E,
                 Mux((is_T4 && is_T2) || (is_T3 && is_T2), T2_result_E,
                 Mux((is_T4 && is_T1) || (is_T3 && is_T1) || (is_T2 && is_T1), T1_result_E,
                     Cat(T0_tage(io.pcE(7,0)), 0.U(6.W)))))

  // Sequential update
  GHR_fifo := GHR
  when (io.branchE) {
    GHR := Cat(GHR(62,0), io.actualTakeE)

    when (io.actualTakeE) {
      BHT(io.pcE(6,0)) := io.pcETarget
    }

    // T4 update
    when (is_T4) {
      when (T4_result_E(8) === io.actualTakeE) {
        // strengthen counter
        T4_tage(hash_T4_E) := Cat(
          Mux(T4_result_E(8,6) === "b111".U, T4_result_E(8,6), T4_result_E(8,6) + 1.U),
          T4_result_E(5,2),
          Mux(T4_result_E(8) =/= alt_tage(8),
              Mux(T4_result_E(1,0) === "b11".U, T4_result_E(1,0), T4_result_E(1,0) + 1.U),
              T4_result_E(1,0))
        )
      } .otherwise {
        // weaken counter
        T4_tage(hash_T4_E) := Cat(
          Mux(T4_result_E(8,6) === "b000".U, T4_result_E(8,6), T4_result_E(8,6) - 1.U),
          T4_result_E(5,2),
          Mux(T4_result_E(8) =/= alt_tage(8),
              Mux(T4_result_E(1,0) === "b00".U, T4_result_E(1,0), T4_result_E(1,0) - 1.U),
              T4_result_E(1,0))
        )
      }
    } .elsewhen (is_T3) {
      // T3 and possibly install new entries in T4
      when (T3_result_E(8) === io.actualTakeE) {
        T3_tage(hash_T3_E) := Cat(
          Mux(T3_result_E(8,6) === "b111".U, T3_result_E(8,6), T3_result_E(8,6) + 1.U),
          T3_result_E(5,2),
          Mux(T3_result_E(8) =/= alt_tage(8),
              Mux(T3_result_E(1,0) === "b11".U, T3_result_E(1,0), T3_result_E(1,0) + 1.U),
              T3_result_E(1,0))
        )
      } .otherwise {
        T3_tage(hash_T3_E) := Cat(
          Mux(T3_result_E(8,6) === "b000".U, T3_result_E(8,6), T3_result_E(8,6) - 1.U),
          T3_result_E(5,2),
          Mux(T3_result_E(8) =/= alt_tage(8),
              Mux(T3_result_E(1,0) === "b00".U, T3_result_E(1,0), T3_result_E(1,0) - 1.U),
              T3_result_E(1,0))
        )
      
      when (T4_result_E(1,0) === "b00".U) {
        T4_tage(hash_T4_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T4_E,
          "b00".U
        )
      } .otherwise {
        T4_tage(hash_T4_E) := Cat(
          T4_result_E(8,6),
          T4_result_E(5,2),
          T4_result_E(1,0) - 1.U
        )
      }}
    } .elsewhen (is_T2) {
      // T2 update and possible installs
      when (T2_result_E(8) === io.actualTakeE) {
        T2_tage(hash_T2_E) := Cat(
          Mux(T2_result_E(8,6) === "b111".U, T2_result_E(8,6), T2_result_E(8,6) + 1.U),
          T2_result_E(5,2),
          Mux(T2_result_E(8) =/= alt_tage(8),
              Mux(T2_result_E(1,0) === "b11".U, T2_result_E(1,0), T2_result_E(1,0) + 1.U),
              T2_result_E(1,0))
        )
      } .otherwise {
        T2_tage(hash_T2_E) := Cat(
          Mux(T2_result_E(8,6) === "b000".U, T2_result_E(8,6), T2_result_E(8,6) - 1.U),
          T2_result_E(5,2),
          Mux(T2_result_E(8) =/= alt_tage(8),
              Mux(T2_result_E(1,0) === "b00".U, T2_result_E(1,0), T2_result_E(1,0) - 1.U),
              T2_result_E(1,0))
        )
      
      when (T3_result_E(1,0) === "b00".U) {
        T3_tage(hash_T3_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T3_E,
          "b00".U
        )
      } .elsewhen (T4_result_E(1,0) === "b00".U) {
        T4_tage(hash_T4_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T4_E,
          "b00".U
        )
      } .otherwise {
        T4_tage(hash_T4_E) := Cat(
          T4_result_E(8,6),
          T4_result_E(5,2),
          T4_result_E(1,0) - 1.U
        )
        T3_tage(hash_T3_E) := Cat(
          T3_result_E(8,6),
          T3_result_E(5,2),
          T3_result_E(1,0) - 1.U
        )
      }}
    } .elsewhen (is_T1) {
      // T1 update and installs
      when (T1_result_E(8) === io.actualTakeE) {
        T1_tage(hash_T1_E) := Cat(
          Mux(T1_result_E(8,6) === "b111".U, T1_result_E(8,6), T1_result_E(8,6) + 1.U),
          T1_result_E(5,2),
          Mux(T1_result_E(8) =/= alt_tage(8),
              Mux(T1_result_E(1,0) === "b11".U, T1_result_E(1,0), T1_result_E(1,0) + 1.U),
              T1_result_E(1,0))
        )
      } .otherwise {
        T1_tage(hash_T1_E) := Cat(
          Mux(T1_result_E(8,6) === "b000".U, T1_result_E(8,6), T1_result_E(8,6) - 1.U),
          T1_result_E(5,2),
          Mux(T1_result_E(8) =/= alt_tage(8),
              Mux(T1_result_E(1,0) === "b00".U, T1_result_E(1,0), T1_result_E(1,0) - 1.U),
              T1_result_E(1,0))
        )
      
      when (T2_result_E(1,0) === "b00".U) {
        T2_tage(hash_T2_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T2_E,
          "b00".U
        )
      } .elsewhen (T3_result_E(1,0) === "b00".U) {
        T3_tage(hash_T3_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T3_E,
          "b00".U
        )
      } .elsewhen (T4_result_E(1,0) === "b00".U) {
        T4_tage(hash_T4_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T4_E,
          "b00".U
        )
      } .otherwise {
        T4_tage(hash_T4_E) := Cat(
          T4_result_E(8,6),
          T4_result_E(5,2),
          T4_result_E(1,0) - 1.U
        )
        T3_tage(hash_T3_E) := Cat(
          T3_result_E(8,6),
          T3_result_E(5,2),
          T3_result_E(1,0) - 1.U
        )
        T2_tage(hash_T2_E) := Cat(
          T2_result_E(8,6),
          T2_result_E(5,2),
          T2_result_E(1,0) - 1.U
        )
        T1_tage(hash_T1_E) := Cat(
          T1_result_E(8,6),
          T1_result_E(5,2),
          T1_result_E(1,0) - 1.U
        )
      }}
    } .otherwise {
      // T0 update fallback
      when (T0_result_E(2) === io.actualTakeE) {
        T0_tage(io.pcE(7,0)) := Mux(T0_result_E(2,0) === "b111".U, 
                                      T0_result_E(2,0), 
                                      T0_result_E(2,0) + 1.U)
      } .otherwise {
        T0_tage(io.pcE(7,0)) := Mux(T0_result_E(2,0) === "b000".U, 
                                      T0_result_E(2,0), 
                                      T0_result_E(2,0) - 1.U)
      
      // possible installs into higher tables
      when (T1_result_E(1,0) === "b00".U) {
        T1_tage(hash_T1_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T1_E,
          "b00".U
        )
      } .elsewhen (T2_result_E(1,0) === "b00".U) {
        T2_tage(hash_T2_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T2_E,
          "b00".U
        )
      } .elsewhen (T3_result_E(1,0) === "b00".U) {
        T3_tage(hash_T3_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T3_E,
          "b00".U
        )
      } .elsewhen (T4_result_E(1,0) === "b00".U) {
        T4_tage(hash_T4_E) := Cat(
          Mux(io.actualTakeE, "b100".U, "b011".U),
          tag_sel_T4_E,
          "b00".U
        )
      } .otherwise {
        T4_tage(hash_T4_E) := Cat(
          T4_result_E(8,6),
          T4_result_E(5,2),
          T4_result_E(1,0) - 1.U
        )
        T3_tage(hash_T3_E) := Cat(
          T3_result_E(8,6),
          T3_result_E(5,2),
          T3_result_E(1,0) - 1.U
        )
        T2_tage(hash_T2_E) := Cat(
          T2_result_E(8,6),
          T2_result_E(5,2),
          T2_result_E(1,0) - 1.U
        )
        T1_tage(hash_T1_E) := Cat(
          T1_result_E(8,6),
          T1_result_E(5,2),
          T1_result_E(1,0) - 1.U
        )
      }
    }}
  }
}

object BTB extends App {
  println(getVerilogString(new BTB()))
}
