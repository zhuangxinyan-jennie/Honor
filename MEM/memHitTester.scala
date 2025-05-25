package mem

import chisel3.iotesters.{PeekPokeTester, ChiselFlatSpec, Driver}

class MEMHitTest(c: MEM) extends PeekPokeTester(c) {
  val baseAddr = 0x1000

  //sFectline and sCheckcache when miss(load)
  poke(c.io.cpu.mem_en, 1)
  poke(c.io.cpu.mem_write,0)
  poke(c.io.cpu.mem_type, 2)
  poke(c.io.cpu.mem_unsigned,0)
  poke(c.io.cpu.mem_addr, baseAddr)
  poke(c.io.sram.rdata,0x00001234)

  step(10)
  expect(c.io.cpu.mem_ready,1)
  expect(c.io.cpu.mem_rdata,0x00001234)
  expect(c.io.mem_bypass_data,0x00001234)
  println(s"hit = ${peek(c.io.debug_hit)}") // 需要为状态机添加可观察节点
  println(s"mem_ready = ${peek(c.io.cpu.mem_ready)}")
  println(s"state = ${peek(c.io.debug_state)}") // 需要为状态机添加可观察节点
  println(s"rdata = ${peek(c.io.debug_rdata)}")

  poke(c.io.cpu.mem_en,0)

  step(4)

  //sCheckcache when hit(store)
  poke(c.io.cpu.mem_en,1)
  poke(c.io.cpu.mem_write,1)
  poke(c.io.cpu.mem_type,2)
  poke(c.io.cpu.mem_unsigned,0)
  poke(c.io.cpu.mem_addr,baseAddr)
  poke(c.io.cpu.mem_wdata,0x00000123)


  step(3)
  println(s"state = ${peek(c.io.debug_state)}") // 需要为状态机添加可观察节点
  println(s"hit = ${peek(c.io.debug_hit)}") // 需要为状态机添加可观察节点
  println(s"ready = ${peek(c.io.cpu.mem_ready)}") // 需要为状态机添加可观察节点
  println(s"ramAddr_save = ${peek(c.io.debug_ramAddr)}")
  println(s"ramAddr_data = ${peek(c.io.debug_newData)}")
  expect(c.io.sram.wdata,0x00000123)
  expect(c.io.cpu.mem_ready,1)


  poke(c.io.cpu.mem_en,0)

  step(4)

  //sCheckcache when hit(load)
  poke(c.io.cpu.mem_en, 1)
  poke(c.io.cpu.mem_write,0)
  poke(c.io.cpu.mem_type, 2)
  poke(c.io.cpu.mem_unsigned,0)
  poke(c.io.cpu.mem_addr, baseAddr)
  poke(c.io.sram.rdata,0x00000123)


  step(13)
  expect(c.io.cpu.mem_ready,1)
  expect(c.io.cpu.mem_rdata,0x00000123)
  expect(c.io.mem_bypass_data,0x00000123)
  println(s"mem_ready = ${peek(c.io.cpu.mem_ready)}")
  println(s"state = ${peek(c.io.debug_state)}") // 需要为状态机添加可观察节点
}

class MEMHitTester extends ChiselFlatSpec {
  // 基础测试用例
  "MEM" should "return cached data immediately" in {
    Driver.execute(Array("--backend-name", "treadle"), () => new MEM) { c =>
      new MEMHitTest(c)
    } should be(true)
  }
}