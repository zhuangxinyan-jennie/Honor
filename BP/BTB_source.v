`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 2025/05/23 18:22:42
// Design Name: 
// Module Name: new_BTB
// Project Name: 
// Target Devices: 
// Tool Versions: 
// Description: 
// 
// Dependencies: 
// 
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
// 
//////////////////////////////////////////////////////////////////////////////////


module BTB(
    input clk,rst,enaD,branchE,actualTakeE,
    input [31:0] instrD,pcD,pcE,pcETarget,
    output predTakeD,
    output [31:0] branchTargetD
    );
    wire is_jmp;
    assign is_jmp=(instrD[6:0]==7'b1100111)|(instrD[6:0]==7'b1100011)|(instrD[6:0]==7'b1101111);
    
    //GHR    
    reg [63:0] GHR;
    
    //T0_tage_output
    reg [2:0] T0_tage [255:0];
    wire [2:0] T0_result;
    assign T0_result=T0_tage[pcD[7:0]];    
    
    //T1_tage_output
    wire [7:0] hash_T1;
    wire [3:0] tag_sel_T1;
    assign hash_T1=pcD[31:24]^pcD[23:16]^pcD[15:8]^pcD[7:0]^GHR[7:0];
    reg [8:0] T1_tage [255:0];
    wire [8:0] T1_result;
    assign tag_sel_T1=hash_T1[7:4]^hash_T1[3:0];
    assign T1_result=T1_tage[hash_T1];
    
    //T2_tage_output
    wire [7:0] hash_T2;
    wire [3:0] tag_sel_T2;
    assign hash_T2=pcD[31:24]^pcD[23:16]^pcD[15:8]^pcD[7:0]^GHR[7:0]^GHR[15:8];
    reg [8:0] T2_tage [255:0];
    wire [8:0] T2_result;
    assign tag_sel_T2=hash_T2[7:4]^hash_T2[3:0];
    assign T2_result=T2_tage[hash_T2];
    
    //T3_tage_output
    wire [7:0] hash_T3;
    wire [3:0] tag_sel_T3;
    assign hash_T3=pcD[31:24]^pcD[23:16]^pcD[15:8]^pcD[7:0]^GHR[7:0]^GHR[15:8]^GHR[23:16]^GHR[31:24];
    reg [8:0] T3_tage [255:0];
    wire [8:0] T3_result;
    assign tag_sel_T3=hash_T3[7:4]^hash_T3[3:0];
    assign T3_result=T3_tage[hash_T3]; 

    //T4_tage_output
    wire [7:0] hash_T4;
    wire [3:0] tag_sel_T4;
    assign hash_T4=pcD[31:24]^pcD[23:16]^pcD[15:8]^pcD[7:0]^GHR[7:0]^GHR[15:8]^GHR[23:16]^GHR[31:24]^GHR[39:32]^GHR[47:40]^GHR[55:48]^GHR[63:56];
    reg [8:0] T4_tage [255:0];
    wire [8:0] T4_result;
    assign tag_sel_T4=hash_T4[7:4]^hash_T4[3:0];
    assign T4_result=T4_tage[hash_T4];       
    
    //output
    assign predTakeD=(is_jmp&enaD)?(
                    (T4_result[5:2]==tag_sel_T4)?T4_result[8]:
                    (T3_result[5:2]==tag_sel_T3)?T3_result[8]:
                    (T2_result[5:2]==tag_sel_T2)?T2_result[8]:
                    (T1_result[5:2]==tag_sel_T1)?T1_result[8]:T0_result[2]) :1'b0;

    //BHT
    reg [31:0] BHT [127:0];
    assign branchTargetD=BHT[pcD[6:0]];
    
    
    
    
    //E_mode
    
    reg [63:0] GHR_fifo;
        
    //T0_tage_input
    wire [2:0] T0_result_E;
    assign T0_result_E=T0_tage[pcE[7:0]];    
    
    //T1_tage_input
    wire [7:0] hash_T1_E;
    wire [3:0] tag_sel_T1_E;
    assign hash_T1_E=pcE[31:24]^pcE[23:16]^pcE[15:8]^pcE[7:0]^GHR_fifo[7:0];
    wire [8:0] T1_result_E;
    assign tag_sel_T1_E=hash_T1_E[7:4]^hash_T1_E[3:0];
    assign T1_result_E=T1_tage[hash_T1_E];
    
    //T2_tage_input
    wire [7:0] hash_T2_E;
    wire [3:0] tag_sel_T2_E;
    assign hash_T2_E=pcE[31:24]^pcE[23:16]^pcE[15:8]^pcE[7:0]^GHR_fifo[7:0]^GHR_fifo[15:8];
    wire [8:0] T2_result_E;
    assign tag_sel_T2_E=hash_T2_E[7:4]^hash_T2_E[3:0];
    assign T2_result_E=T2_tage[hash_T2_E];
    
    //T3_tage_input
    wire [7:0] hash_T3_E;
    wire [3:0] tag_sel_T3_E;
    assign hash_T3_E=pcE[31:24]^pcE[23:16]^pcE[15:8]^pcE[7:0]^GHR_fifo[7:0]^GHR_fifo[15:8]^GHR_fifo[23:16]^GHR_fifo[31:24];
    wire [8:0] T3_result_E;
    assign tag_sel_T3_E=hash_T3_E[7:4]^hash_T3_E[3:0];
    assign T3_result_E=T3_tage[hash_T3_E]; 

    //T4_tage_output
    wire [7:0] hash_T4_E;
    wire [3:0] tag_sel_T4_E;
    assign hash_T4_E=pcE[31:24]^pcE[23:16]^pcE[15:8]^pcE[7:0]^GHR_fifo[7:0]^GHR_fifo[15:8]^GHR_fifo[23:16]^GHR_fifo[31:24]^GHR_fifo[39:32]^GHR_fifo[47:40]^GHR_fifo[55:48]^GHR_fifo[63:56];
    wire [8:0] T4_result_E;
    assign tag_sel_T4_E=hash_T4_E[7:4]^hash_T4_E[3:0];
    assign T4_result_E=T4_tage[hash_T4_E];
    
    //flag
    wire is_T4=(T4_result_E[5:2]==tag_sel_T4_E);
    wire is_T3=(T3_result_E[5:2]==tag_sel_T3_E);
    wire is_T2=(T2_result_E[5:2]==tag_sel_T2_E);
    wire is_T1=(T1_result_E[5:2]==tag_sel_T1_E);            
    wire [8:0] alt_tage;
    assign alt_tage=(is_T4&is_T3)?T3_result_E:
                    ((is_T4&is_T2)|(is_T3&is_T2))?T2_result_E:
                    ((is_T4&is_T1)|(is_T3&is_T1)|(is_T2&is_T1))?T1_result_E:
                    {T0_result_E,6'd0};
    
    
    //sequence    
    integer i;
    always@(posedge clk,rst)
    begin
        if(rst)
        begin
            GHR <= 64'b0;
            GHR_fifo <= 64'b0;
            for(i = 0; i < 256; i = i + 1) begin
                T0_tage[i] <= 3'b000;
                T1_tage[i] <= 9'b000000000;
                T2_tage[i] <= 9'b000000000;
                T3_tage[i] <= 9'b000000000;
                T4_tage[i] <= 9'b000000000;
            end
            for(i = 0; i < 128; i = i + 1) begin
                BHT[i] <= 32'b0;
            end                
        end
        
        else
        begin 
        GHR_fifo<=GHR;
        if(branchE)
        //load
        begin
        GHR[63:1]<=GHR[62:0];
        GHR[0]<=actualTakeE;
        
        BHT[pcE[6:0]]<=pcETarget;
        
        if(is_T4)
            begin
            if(T4_result_E[8]==actualTakeE)
                begin
                case(T4_result_E[8:6])
                3'b111:T4_tage[hash_T4_E][8:6]<=T4_result_E[8:6];
                default:T4_tage[hash_T4_E][8:6]<=T4_result_E[8:6]+3'd1;
                endcase
                if(T4_result_E[8]!=alt_tage[8])
                begin
                case(T4_result_E[1:0])
                2'b11:T4_tage[hash_T4_E][1:0]<=T4_result_E[1:0];
                default:T4_tage[hash_T4_E][1:0]<=T4_result_E[1:0]+2'd1;
                endcase
                end
                end
            else
                begin
                case(T4_result_E[8:6])
                3'b000:T4_tage[hash_T4_E][8:6]<=T4_result_E[8:6];
                default:T4_tage[hash_T4_E][8:6]<=T4_result_E[8:6]-3'd1;
                endcase
                if(T4_result_E[8]!=alt_tage[8])
                begin
                case(T4_result_E[1:0])
                2'b00:T4_tage[hash_T4_E][1:0]<=T4_result_E[1:0];
                default:T4_tage[hash_T4_E][1:0]<=T4_result_E[1:0]-2'd1;
                endcase
                end
                end
            end
            
        else if(is_T3)
            begin
            if(T3_result_E[8]==actualTakeE)
                begin
                case(T3_result_E[8:6])
                3'b111:T3_tage[hash_T3_E][8:6]<=T3_result_E[8:6];
                default:T3_tage[hash_T3_E][8:6]<=T3_result_E[8:6]+3'd1;
                endcase
                if(T3_result_E[8]!=alt_tage[8])
                begin
                case(T3_result_E[1:0])
                2'b11:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0];
                default:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0]+2'd1;
                endcase
                end
                end
            else
                begin
                case(T3_result_E[8:6])
                3'b000:T3_tage[hash_T3_E][8:6]<=T3_result_E[8:6];
                default:T3_tage[hash_T3_E][8:6]<=T3_result_E[8:6]-3'd1;
                endcase
                if(T3_result_E[8]!=alt_tage[8])
                begin
                case(T3_result_E[1:0])
                2'b00:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0];
                default:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0]-2'd1;
                endcase
                end                             
                
                if(T4_tage[hash_T4_E][1:0]==2'b00)
                    begin
                    T4_tage[hash_T4_E][5:2]<=tag_sel_T4_E;
                    if(actualTakeE)
                    T4_tage[hash_T4_E][8:6]<=3'b100;
                    else
                    T4_tage[hash_T4_E][8:6]<=3'b011;
                    end
                else
                    begin
                    case(T4_tage[hash_T4_E][1:0])
                    2'b00:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0];
                    default:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0]-2'd1;
                    endcase         
                    end
                end
            end
            
        else if(is_T2)
            begin
            if(T2_result_E[8]==actualTakeE)
                begin
                case(T2_result_E[8:6])
                3'b111:T2_tage[hash_T2_E][8:6]<=T2_result_E[8:6];
                default:T2_tage[hash_T2_E][8:6]<=T2_result_E[8:6]+3'd1;
                endcase
                if(T2_result_E[8]!=alt_tage[8])
                begin
                case(T2_result_E[1:0])
                2'b11:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0];
                default:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0]+2'd1;
                endcase
                end
                end
            else
                begin
                case(T2_result_E[8:6])
                3'b000:T2_tage[hash_T2_E][8:6]<=T2_result_E[8:6];
                default:T2_tage[hash_T2_E][8:6]<=T2_result_E[8:6]-3'd1;
                endcase
                if(T2_result_E[8]!=alt_tage[8])
                begin
                case(T2_result_E[1:0])
                2'b00:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0];
                default:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0]-2'd1;
                endcase
                end                             
                
                if(T3_result[1:0]==2'b00)
                    begin
                    T3_tage[hash_T3_E][5:2]<=tag_sel_T3_E;
                    if(actualTakeE)
                    T3_tage[hash_T3_E][8:6]<=3'b100;
                    else
                    T3_tage[hash_T3_E][8:6]<=3'b011;
                    end                
                else if(T4_result[1:0]==2'b00)
                    begin
                    T4_tage[hash_T4_E][5:2]<=tag_sel_T4_E;
                    if(actualTakeE)
                    T4_tage[hash_T4_E][8:6]<=3'b100;
                    else
                    T4_tage[hash_T4_E][8:6]<=3'b011;
                    end
                else
                    begin
                    case(T4_tage[hash_T4_E][1:0])
                    2'b00:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0];
                    default:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0]-2'd1;
                    endcase   
                    case(T3_result_E[1:0])
                    2'b00:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0];
                    default:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0]-2'd1;
                    endcase      
                    end
                end
            end
            
        else if(is_T1)
            begin
            if(T1_result_E[8]==actualTakeE)
                begin
                case(T1_result_E[8:6])
                3'b111:T1_tage[hash_T1_E][8:6]<=T1_result_E[8:6];
                default:T1_tage[hash_T1_E][8:6]<=T1_result_E[8:6]+3'd1;
                endcase
                if(T1_result_E[8]!=alt_tage[8])
                begin
                case(T1_result_E[1:0])
                2'b11:T1_tage[hash_T1_E][1:0]<=T1_result_E[1:0];
                default:T1_tage[hash_T1_E][1:0]<=T1_result_E[1:0]+2'd1;
                endcase
                end
                end
            else
                begin
                case(T1_result_E[8:6])
                3'b000:T1_tage[hash_T1_E][8:6]<=T1_result_E[8:6];
                default:T1_tage[hash_T1_E][8:6]<=T1_result_E[8:6]-3'd1;
                endcase
                if(T1_result_E[8]!=alt_tage[8])
                begin
                case(T1_result_E[1:0])
                2'b00:T1_tage[hash_T1_E][1:0]<=T1_result_E[1:0];
                default:T1_tage[hash_T1_E][1:0]<=T1_result_E[1:0]-2'd1;
                endcase
                end                             
                
                if(T2_result[1:0]==2'b00)
                    begin
                    T2_tage[hash_T2_E][5:2]<=tag_sel_T2_E;
                    if(actualTakeE)
                    T2_tage[hash_T2_E][8:6]<=3'b100;
                    else
                    T2_tage[hash_T2_E][8:6]<=3'b011;
                    end    
                else if(T3_result[1:0]==2'b00)
                    begin
                    T3_tage[hash_T3_E][5:2]<=tag_sel_T3_E;
                    if(actualTakeE)
                    T3_tage[hash_T3_E][8:6]<=3'b100;
                    else
                    T3_tage[hash_T3_E][8:6]<=3'b011;
                    end                
                else if(T4_result[1:0]==2'b00)
                    begin
                    T4_tage[hash_T4_E][5:2]<=tag_sel_T4_E;
                    if(actualTakeE)
                    T4_tage[hash_T4_E][8:6]<=3'b100;
                    else
                    T4_tage[hash_T4_E][8:6]<=3'b011;
                    end
                else
                    begin
                    case(T4_tage[hash_T4_E][1:0])
                    2'b00:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0];
                    default:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0]-2'd1;
                    endcase   
                    case(T3_result_E[1:0])
                    2'b00:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0];
                    default:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0]-2'd1;
                    endcase      
                    case(T2_result_E[1:0])
                    2'b00:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0];
                    default:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0]-2'd1;
                    endcase   
                    end
                end
            end
                
        else
            begin
            if(T0_result_E[2]==actualTakeE)
                begin
                case(T0_result_E[2:0])
                3'b111:T0_tage[pcE[7:0]][2:0]<=T0_result_E[2:0];
                default:T0_tage[pcE[7:0]][2:0]<=T0_result_E[2:0]+3'd1;
                endcase                
                end
            else
                begin
                case(T0_result_E[2:0])
                3'b000:T0_tage[pcE[7:0]][2:0]<=T0_result_E[2:0];
                default:T0_tage[pcE[7:0]][2:0]<=T0_result_E[2:0]-3'd1;
                endcase                                         
                
                if(T1_result[1:0]==2'b00)
                    begin
                    T1_tage[hash_T1_E][5:2]<=tag_sel_T1_E;
                    if(actualTakeE)
                    T1_tage[hash_T1_E][8:6]<=3'b100;
                    else
                    T1_tage[hash_T1_E][8:6]<=3'b011;
                    end    
                else if(T2_result[1:0]==2'b00)
                    begin
                    T2_tage[hash_T2_E][5:2]<=tag_sel_T2_E;
                    if(actualTakeE)
                    T2_tage[hash_T2_E][8:6]<=3'b100;
                    else
                    T2_tage[hash_T2_E][8:6]<=3'b011;
                    end    
                else if(T3_result[1:0]==2'b00)
                    begin
                    T3_tage[hash_T3_E][5:2]<=tag_sel_T3_E;
                    if(actualTakeE)
                    T3_tage[hash_T3_E][8:6]<=3'b100;
                    else
                    T3_tage[hash_T3_E][8:6]<=3'b011;
                    end                
                else if(T4_result[1:0]==2'b00)
                    begin
                    T4_tage[hash_T4_E][5:2]<=tag_sel_T4_E;
                    if(actualTakeE)
                    T4_tage[hash_T4_E][8:6]<=3'b100;
                    else
                    T4_tage[hash_T4_E][8:6]<=3'b011;
                    end
                else
                    begin
                    case(T4_tage[hash_T4_E][1:0])
                    2'b00:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0];
                    default:T4_tage[hash_T4_E][1:0]<=T4_tage[hash_T4_E][1:0]-2'd1;
                    endcase   
                    case(T3_result_E[1:0])
                    2'b00:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0];
                    default:T3_tage[hash_T3_E][1:0]<=T3_result_E[1:0]-2'd1;
                    endcase      
                    case(T2_result_E[1:0])
                    2'b00:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0];
                    default:T2_tage[hash_T2_E][1:0]<=T2_result_E[1:0]-2'd1;
                    endcase   
                    case(T1_result_E[1:0])
                    2'b00:T1_tage[hash_T1_E][1:0]<=T1_result_E[1:0];
                    default:T1_tage[hash_T1_E][1:0]<=T1_result_E[1:0]-2'd1;
                    endcase  
                    end
                end
            end                
                
                

        
        end
        end
    end                                
           
endmodule
