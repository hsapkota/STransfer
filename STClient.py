#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Created on Fri Jan 10 18:25:34 2020

@author: hem
"""

import socket
import sys
import random as rd
import math
import time
from threading import Thread
import bisect as _bisect
import argparse
# from statsmodels.tsa.ar_model import AR
import warnings

def print_message(message):
    print(message)


class STClient:
    def __init__(self, args):
        self.max_write_threads = 10
        self.max_read_threads = 10
        self.max_transfer_threads = 10
        self.max_write_queue = 100
        self.max_buffer_size = 128*1024
        self.max_read_queue = 100
        
        self.debug = False
        self.total_transfer_done = 0
        self.transfer_done = False
        self.stop_time = 0
        self.transfer_port = 0
        self.start_time = 0
        if(not args.sender or not args.receiver):
            print("[+] Sender and receiver not specified")
            exit(0)
        sender_info = self.parse_IP(args.sender)
        receiver_info = self.parse_IP(args.receiver)
        self.sender_ip = sender_info[0]
        self.sender_port = int(sender_info[1])
        self.sender_path = sender_info[2]
        print_message("[Sender] is %s:%d%s"%(self.sender_ip, self.sender_port, self.sender_path))
        
        self.receiver_ip = receiver_info[0]
        self.receiver_port = int(receiver_info[1])
        self.receiver_path = receiver_info[2]
        self.interface_ip = self.receiver_ip
        print_message("[Receiver] is %s:%d%s"%(self.receiver_ip, self.receiver_port, self.receiver_path))
        if(args.interface):
            self.interface_ip = args.interface
            
    def parse_IP(self, ip):
        parsed_ip = []
        messages = ip.split(":")
        parsed_ip.append(messages[0])
        if "~" in messages[1]:
            new_msg = messages[1].split("~")
            parsed_ip.append(new_msg[0])
            parsed_ip.append("~"+new_msg[1])
        else:
            new_msg = messages[1].split("/", 1)
            parsed_ip.append(new_msg[0])
            parsed_ip.append("/"+new_msg[1])
        return parsed_ip



class TalkSend(Thread):
    def __init__(self, st_client_param):
        Thread.__init__(self)
        self.st_client = st_client_param
        self.stop_probing = False
        self.sender_finish_blocks = False
        self.start_current_probing = False
        self.stop_current_probing = False
        self.can_start_probing = False
        self.current_probe_started = False
        self.parameters = ""
        self.parameter_list = {}
        self.serversocket = None
        self.client_data, self.client_addr = None, None
        
    def send_message(self, message):
        message = str(message)+"\n"
        self.socket.send(message.encode())
    def get_next_line(self):
        message = self.socket.recv(1024).decode()
        while("\n" not in message):
            message += self.socket.recv(1024).decode()
        message=message.strip()
        return message
    
    def probe(self):
        self.send_message("Parameter:"+self.parameters)
        message = self.get_next_line()
        if(message == "ok"):
            self.sender_finish_blocks = True
        while(not self.start_current_probing):
            time.sleep(0.010)
        self.send_message("Start:currentProbing")
        message = self.get_next_line()
        if(message.lower() == "ok"):
            self.current_probe_started = True
        while(not self.stop_current_probing):
            time.sleep(0.010)
            self.send_message("Check:done")
            message = self.get_next_line()
            msg = message.strip().split(":")
            if(msg[0].lower() == "true"):
                self.st_client.stop_time = int(time.time()*1000)
                self.st_client.transfer_done = True
                if(len(msg) >= 2):
                    self.st_client.total_transfer_done = int(msg[1].strip())
                self.stop_current_probing = True
                self.stop_probing = True
                return True
        self.send_message("Stop:currentProbing")
        message = self.get_next_line()
        
        self.stop_current_probing = False
        self.current_probe_started = False
        self.sender_finish_blocks = False
        return True
        
    def run(self):
        self.socket = socket.socket()
#        self.server_socket.bind((self.st_client.sender_ip, self.st_client.sender_port))
        self.client_data = self.socket.connect((self.st_client.sender_ip, self.st_client.sender_port))

        self.send_message(str(self.st_client.interface_ip))
        while(self.st_client.transfer_port == 0):
            time.sleep(10)
        #Sender data
        self.send_message(str(self.st_client.transfer_port))
        self.send_message(str(self.st_client.sender_path))
        
        #Max Parameters
        self.send_message(str(self.st_client.max_transfer_threads))
        self.send_message(str(self.st_client.max_read_threads))
        self.send_message(str(self.st_client.max_read_queue))
        
        self.send_message("Start:Probing")
        self.get_next_line()
        self.can_start_probing = True
        self.st_client.start_time = int(time.time()*1000)
        print_message("Starting time is: %d"%self.st_client.start_time)
        
        while(not self.stop_probing):
            while(self.parameters == ""):
                time.sleep(0.010)
            self.probe()
        if not self.st_client.transfer_done:
            self.send_message("Start:Transfer")
            print_message("[Sender] Normal transfer has been started")
            self.get_next_line()
            self.probe()
        self.socket.close()
    def set_parameter(self, param):
        self.parameters = param
        

class TalkReceive(Thread):
    def __init__(self, st_client_param, st_send_param):
        Thread.__init__(self)
        self.stop_probing = False
        self.start_current_probing = False
        self.stop_current_probing = False
        self.convergence_found = False
        self.can_start_probing = False
        self.parameters = ""
        self.parameter_list = {}
        self.thpt_list = []
        self.talk_send = st_send_param
        self.st_client = st_client_param
        self.serversocket = None
        self.client_data, self.client_addr = None, None
        self.zero_count = 0
        self.should_end = False
        
    def send_message(self, message):
        message = str(message)+"\n"
        try:
            self.socket.send(message.encode())
        except:
            self.should_end = True
    def get_next_line(self):
        try:
            message = self.socket.recv(1024).decode()
            while("\n" not in message):
                message += self.socket.recv(1024).decode()
            message=message.strip()
        except:
            message = "the:transfer:is:completed"
            self.should_end = True
        return message
    def add_throughput(self, message, throughput_started):
        if(message != ""):
            thpts = message.split(",")
            for i in thpts:
                if(i != ""):
                    throughput = 0.
                    try:
                        throughput = float(i)
                    except:
                        pass
                    if(throughput <= 0.000001 and not len(self.thpt_list) and not throughput_started):
                        self.zero_count += 1
                        if self.zero_count > 15:
                            break
                        continue
                    throughput_started = True
                    self.thpt_list.append(throughput)
        return throughput_started
    def probe(self):
        self.zero_count = 0
        self.send_message("Parameter:"+self.parameters)
        message = self.get_next_line()
        if(self.should_end == True):
            return
        if(message.lower() == "ok"):
            self.talk_send.start_current_probing = True
        while(not self.talk_send.sender_finish_blocks or not self.start_current_probing):
            time.sleep(0.01)
            self.start_current_probing = self.talk_send.current_probe_started
        self.send_message("Start:currentProbing")
        message = self.get_next_line()
        self.thpt_list = []
        throughput_started = False
        while(not self.stop_current_probing):
            self.send_message("Get Throughput:New")
            if(self.should_end == True):
                return
            message = self.get_next_line()
            throughput_started = self.add_throughput(message, throughput_started)
            time.sleep(0.01)
        tmp = self.thpt_list
        self.thpt_list = [0]*self.zero_count + tmp
        self.talk_send.stop_current_probing = True
        self.send_message("Stop:currentProbing")
        message = self.get_next_line()
        self.stop_current_probing = False
        self.start_current_probing = False
        self.talk_send.start_current_probing = False

    
    def run(self):
        self.socket = socket.socket()
        self.client_data = self.socket.connect((self.st_client.receiver_ip, self.st_client.receiver_port))
        print_message("Receiver: %s and port: %s" % (self.st_client.receiver_ip, str(self.st_client.receiver_port)))
        self.send_message(self.st_client.receiver_path)
        self.send_message(self.st_client.max_transfer_threads)
        self.send_message(self.st_client.max_write_threads)
        self.send_message(self.st_client.max_write_queue)
        self.send_message(self.st_client.max_buffer_size)
        self.st_client.transfer_port = int(self.get_next_line())
        self.send_message("Start:Probing")
        self.get_next_line()
        self.st_client.start_time = int(time.time()*1000)
        
        self.can_start_probing = True
        while(not self.stop_probing):
            while(self.parameters == ""):
                time.sleep(0.01)
            self.probe()
        if(not self.st_client.transfer_done):
            self.send_message("Start:Transfer")
            print_message("[Receiver] Normal transfer has been started")
            self.get_next_line()
            self.probe()
        
        self.socket.close()
    def set_parameter(self, param):
        self.parameters = param



class GA:
    def __init__(self, args):
        self.number_of_generations = 4
        self.number_of_population = 6
        if(args.generation):
            self.number_of_generations = args.generation
        if(args.population):
            self.number_of_population = args.population
        self.evaluation = "adaptive"
        self.crossover_type = "favour_zero"
        self.mutation_type = "bit_flip"
        self.selection_type = "standard_deviation_elitist"
        
        if(args.manual):
            self.params = [[args.num, 128], [37, 256], [12, 260], [1, 132], [1, 256], [1, 128]]
            self.param_length = [0,0,0,0,0,0]
        else:
            self.params = [[1, 128], [1, 256], [10, 260], [50, 132], [1, 128], [1, 128]]
            self.param_length = [0,0,0,0,4,5]
        self.population_length = 0
        self.agents = []
        self.talk_send = None
        self.talk_receive = None
        self.st_client = None
        self.best_score = 0
        self.best_pop = ""
        self.previous_best_pop = ""
        self.initiate(args)
        if(not args.manual):
            for i in self.param_length:
                self.population_length += i
            for i in range(self.number_of_population):
                self.agents.append(Agents(population="", population_length=self.population_length))
            # if(self.conv == "dnn"):
            #     self.load_clfs()
            #     print("[+] CLF's loaded")
            # if(self.conv == "rand"):
            #     adaptive_iterative.regression_train()
            #     adaptive_iterative.classification_train()
            #     print("[+] CLF's loaded")
            print("[+] Conv is ", self.conv)
            self.mutation_probab = 1.0#/self.population_length
            self.crossover_probab = 1.0
            print("[+] Method is ", args.method)
            if(args.method.lower() == "random"):
                self.run_random()
            else:
                self.run_GA()
            print_message("Best Population: %s and best score: %d" % (self.best_pop, self.best_score))
            self.talk_send.parameters = self.best_pop
            self.talk_receive.parameters = self.best_pop
        else:
            self.talk_send.parameters = ','.join(str(e[0]) for e in self.params) #self.best_pop
            self.talk_receive.parameters = ','.join(str(e[0]) for e in self.params) #self.best_pop
            self.manual_evaluate()
        
        self.talk_send.stop_probing = True
        self.talk_receive.stop_probing = True
        self.talk_receive.stop_current_probing = True
        while(not self.st_client.transfer_done):
            time.sleep(0.01)
        self.check_transfer_done()
    

    def check_transfer_done(self):
        message = False
        if(self.st_client.transfer_done):
            print_message("Sending message to receiver")
            self.talk_receive.send_message("Done:transfer")
            print_message("Message send to receiver")
            try:
                msg = self.talk_receive.get_next_line()
                print(msg)
                msg = msg.split(":")
                print(msg)
                if("ok" in msg):
                    self.talk_send.stop_probing = True
                    self.talk_receive.stop_probing = True
                    self.st_client.transfer_done = True
                    print_message("[+] Total time for this transfer is %.3f Seconds"%((self.st_client.stop_time - self.st_client.start_time)/1000.))
                    message = True
                    if(len(msg) == 2):
                        print_message(str(self.st_client.total_transfer_done) + " Bytes")
                        print_message("[+] Total file transfered is %.3f Bytes" % (int(self.st_client.total_transfer_done)/(1024.*1024.*1024.)))
                        print_message("[+] Average throughput is %.3f Mbps" % ((1000 * 8 * int(self.st_client.total_transfer_done))/((self.st_client.stop_time - self.st_client.start_time)*1000.*1000.)))
                    print_message("These are the params: " + ','.join(str(e[0]) for e in self.params))
                    time.sleep(0.02)
                    self.talk_send._stop.set()
                    time.sleep(0.05)
                    self.talk_receive._stop.set()
                    exit(0)
            except:
                self.talk_send.stop_probing = True
                self.talk_receive.stop_probing = True
                self.st_client.transfer_done = True
                print_message("[+ err] Total time for this transfer is %.3f Seconds"%((self.st_client.stop_time - self.st_client.start_time)/1000.))
                print_message("[+ err] Average throughput is %.3f Mbps" % ((1000 * 8 * int(self.st_client.total_transfer_done))/((self.st_client.stop_time - self.st_client.start_time)*1000.*1000.)))
                message = True
                sys.exit(0)
        return message
   
    def initiate(self, args):
        self.st_client = STClient(args)
        self.st_client.max_read_threads = self.params[0][0]+2**self.param_length[0]+1
        self.st_client.max_transfer_threads = self.params[4][0]+2**self.param_length[4]+1
        self.st_client.max_write_threads = self.params[5][0]+2**self.param_length[5]+1
        self.st_client.start_time = int(time.time()*1000)
        self.talk_send = TalkSend(self.st_client)
        self.talk_receive = TalkReceive(self.st_client, self.talk_send)
        self.talk_send.start()
        self.talk_receive.start()
        self.conv = args.conv
#        self.talk_send.join()
#        self.talk_receive.join()

    def run_GA(self):
        ags = self.agents
        for i in range(self.number_of_generations):
            self.evaluate_population(ags)
#            self.evaluate_population(ags)
            if self.st_client.transfer_done:
                exit(0)
#            if i >= (self.number_of_generations/2):
#                self.best_score = 0.
            ags = self.selection(ags+self.agents, i)
            if (i+1) != self.number_of_generations:
                ags = self.crossover(ags)
                ags = self.mutation(ags)
            if i>1:
                self.agents = ags
            print_message("[+] Current complete generation is: %d"%(i+1))
        self.choose_best_pop()
    def run_random(self):
        self.agents = []
        for i in range(self.number_of_generations*self.number_of_population):
            self.agents.append(Agents(population="", population_length=self.population_length))
        self.evaluate_population(self.agents)
#        self.evaluate_population(self.agents)
        self.selection(self.agents, 0)
        
    def selection(self, agents, generation):
        ags = []
        tmp = []
        
        
        #Comment this out in final version.
        is_chameleon = False
        if(generation == 0 and is_chameleon):
            tmp = agents[:3]
            agents = agents[5:]
        
        
        
        agents = sorted(agents, reverse=True)
        if self.best_score<agents[0].get_score():
            self.previous_best_pop = self.best_pop
            self.best_pop = self.get_param_string(agents[0].get_population())
            self.best_score = agents[0].get_score()
        agents = agents + tmp
        agents = sorted(agents, reverse=True)
        
        if self.selection_type == "elitist":
            agents = agents[:self.number_of_population-5]
            if self.best_pop:
                agents.append(Agents(self.get_bin_string(self.best_pop)))
                agents.append(Agents(self.get_bin_string(self.best_pop)))
                print("%s or %s" % (self.best_pop, self.get_bin_string(self.best_pop)))
#            if self.previous_best_pop:
#                agents.append(Agents(self.get_bin_string(self.previous_best_pop)))
#            agents.append(Agents(self.get_bin_string(self.best_pop)))
            for i in range(self.number_of_population):
                ags.append(rd.choice(agents))
        elif self.selection_type == "ranked":
            for i in range(self.number_of_population):
                ags = self.get_random_choices(agents, weights=self.get_ranking(len(agents)), k=self.number_of_population)
        elif self.selection_type == "percentage_elitist":
            return self.percentage_elitist(agents)
        elif self.selection_type == "standard_deviation_elitist":
            return self.standard_deviation_elitist(agents)
        return ags
    def percentage_elitist(self, agents, higher_than=0.25):
        ags = []
        new_ags = []
        avg_thpt = self.get_average_generation_thpt(agents)
        print_message("[Genaration] average throughput of generation is %.3f Mbps and larger than %.3f Mbps" % (avg_thpt, avg_thpt*(1+higher_than)))
        for agent in agents:
            if agent.get_score() > (1+higher_than) * avg_thpt:
                ags.append(agent)
        if len(ags)<2:
            ags = self.percentage_elitist(agents, higher_than=higher_than-0.05)
        for i in range(len(ags), self.number_of_population):
            new_ags.append(rd.choice(ags))
        return ags+new_ags
    def standard_deviation_elitist(self, agents, number_of_std=1):
        ags = []
        new_ags = []
        avg_thpt = self.get_average_generation_thpt(agents)
        std_ = self.get_generation_std(agents)
        print_message("[Genaration] average throughput of generation is %.3f Mbps and larger than %.3f Mbps" % (avg_thpt, avg_thpt+(number_of_std*std_)))
        for agent in agents:
            if agent.get_score() >= (avg_thpt+(std_*number_of_std)):
                ags.append(agent)
        if len(ags)<2:
            ags = self.standard_deviation_elitist(agents, number_of_std-0.5)
        for i in range(len(ags), self.number_of_population):
            new_ags.append(rd.choice(ags))
        return ags+new_ags
        
    def get_ranking(self, length):
        ranks = [i for i in range(length, 0, -1)]
        sum_r = sum(ranks)
        return [(1.0*i)/sum_r for i in ranks]
        
    def crossover(self, agents):
        ags = []
        for i in range(self.number_of_population//2):
            p1 = agents[2*i].get_population()
            p2 = agents[2*i+1].get_population()
            c1, c2 = p1, p2
            if rd.random() <= self.crossover_probab:
                if self.crossover_type == "single_point":
                    c1, c2 = self.single_point_crossover(p1, p2)
                elif self.crossover_type == "favor_zero":
                    c1, c2 = self.favor_zero(p1, p2)
            ags.append(Agents(c1))
            ags.append(Agents(c2))
        return ags
    def favor_zero(self, p1, p2):
        p1 = self.get_bin_array(p1)
        p2 = self.get_bin_array(p2)
        c1 = ""
        c2 = ""
        for i in range(len(p1)):
            curr_1 = p1[i]
            curr_2 = p2[i]
            for j in range(len(curr_1)):
                char_1 = int(curr_1[j])
                char_2 = int(curr_2[j])
                if char_1 == char_2:
                    c1 += str(char_1)
                    c2 += str(char_2)
                else:
                    if char_1 == 0:
                        c1 += str(char_1)
                        c2 += '0' if rd.random() < 0.5+((len(curr_2)-j)/50) else '1'
                    else:
                        c1 += '0' if rd.random() < 0.5+((len(curr_2)-j)/50) else '1'
                        c2 += str(char_2)
        return c1, c2
    def get_bin_array(self, p1):
        string_array = []
        since_last = 0
        for i in range(len(self.param_length)):
            till = since_last + self.param_length[i]
            current = p1[since_last:till]
            string_array.append(current)
            since_last = till
        return string_array
            
    def single_point_crossover(self, p1, p2):
        index = rd.randint(0, len(p1)-1)
        c1 = p1[:index] + p2[index:]
        c2 = p2[:index] + p1[index:]
        return c1, c2
            
    def mutation(self, agents):
        ags = []
        for agent in agents:
            if rd.random() <= self.mutation_probab:
                if self.mutation_type == "bit_flip":
                    agent = Agents(self.bit_flip_mutation(agent.get_population()))
            ags.append(agent)
        return ags
    
    def bit_flip_mutation(self, population):
        index = rd.randint(0, len(population)-1)
        tmp = "0" if population[index]=="1" else "1"
        return population[:index] + tmp + population[index+1:]
    
    def get_param_string(self, population):
        value_string = ""
        since_last = 0
        for i in range(len(self.param_length)):
            till = since_last + self.param_length[i]
            current = population[since_last:till]
            if i:
                value_string += ","
            since_last = till
            value_string += str(self.get_value(current, i))
        return value_string
    def get_bin_string(self, param_str):
        val_str = param_str.split(",")
        pop = ""
        for i in range(len(val_str)):
            value = str(bin(int(val_str[i]) - self.params[i][0]))[2:]
            pop += self.get_bin_value(value, self.param_length[i])
        return pop
    def get_bin_value(self, value, length):
        if length == 0:
            return ""
        zeros = "0" * (length-len(value))
        return zeros + value
    
    def get_value(self, current, i):
        if not current:
            return self.params[i][0]
        return int(current, 2)+self.params[i][0]

    def evaluate_population(self, agents):
        for agent in agents:
            print_message("[Agent] for agent "+self.get_param_string(agent.get_population()))
            
            evaluation_start = time.time()
            agent.set_thpt(self.evaluate(agent))
            if(self.check_transfer_done()):
                print_message("Transfer is done in %.3f Seconds"%((self.st_client.stop_time - self.st_client.start_time)/1000.))
                break
            print_message("[Agent] for agent %s throughput is: %.3f Mbps avgThpt: %.3f in total time %.3f"%(self.get_param_string(agent.get_population()), agent.get_thpt(), agent.get_avg_thpt(), time.time() - evaluation_start))

    def evaluate(self, agent):
        self.convergence_thpt = {}
        parameter = self.get_param_string(agent.get_population())
        throughput = 0.
        self.talk_receive.set_parameter(parameter)
        self.talk_send.set_parameter(parameter)
        while(not self.talk_send.can_start_probing or not self.talk_receive.can_start_probing):
            time.sleep(0.01)
        self.talk_send.start_current_probing = True
        while(throughput == 0.0 and len(self.talk_receive.thpt_list)<1500 and not self.st_client.transfer_done):
            time.sleep(0.01)
            thpt_list = self.talk_receive.thpt_list
            if len(thpt_list) not in self.convergence_thpt:
                throughput = self.find_convergence(thpt_list)
                self.convergence_thpt[len(thpt_list)] = throughput
            else:
                throughput = self.convergence_thpt[len(thpt_list)]
#        print("[Throughput] list" + str(self.talk_receive.thpt_list))
        if throughput == 0.:
            throughput = self.find_average_thpt(self.talk_receive.thpt_list)
        agent.set_avg_thpt(self.find_average_thpt(self.talk_receive.thpt_list))
        self.talk_receive.thpt_list = []
        self.talk_receive.stop_current_probing = True
        return throughput
    def manual_evaluate(self):
        self.convergence_thpt = {}
        # parameter = self.get_param_string(agent.get_population())
        parameter = self
        throughput = 0.
        self.talk_send.parameters = ','.join(str(e[0]) for e in self.params) #self.best_pop
        self.talk_receive.parameters = ','.join(str(e[0]) for e in self.params) #self.best_pop
        while(not self.talk_send.can_start_probing or not self.talk_receive.can_start_probing):
            time.sleep(0.01)
        self.talk_send.start_current_probing = True
        while(throughput == 0.0 and len(self.talk_receive.thpt_list)<1500 and not self.st_client.transfer_done):
            time.sleep(0.01)
            thpt_list = self.talk_receive.thpt_list
            if len(thpt_list) not in self.convergence_thpt:
                throughput = self.find_convergence(thpt_list)
                self.convergence_thpt[len(thpt_list)] = throughput
            else:
                throughput = self.convergence_thpt[len(thpt_list)]
#        print("[Throughput] list" + str(self.talk_receive.thpt_list))
        if throughput == 0.:
            throughput = self.find_average_thpt(self.talk_receive.thpt_list)
        # agent.set_avg_thpt(self.find_average_thpt(self.talk_receive.thpt_list))
        self.talk_receive.thpt_list = []
        self.talk_receive.stop_current_probing = True
        return throughput
    def find_convergence(self, thpt_list):
        # if(self.conv == "ar"):
        #     return self.find_convergence_timeseries(thpt_list)
        # elif(self.conv == "dnn"):
        #     return self.find_convergence_dnn(thpt_list)
        # elif self.conv == "rand":
        #     if len(thpt_list) >= 2 and adaptive_iterative.is_predictable(thpt_list):
        #         print(thpt_list)
        #         return adaptive_iterative.make_prediction(thpt_list)
        #     else:
        #         return 0.0
        return self.find_average_thpt(thpt_list) if len(thpt_list)>=10 else 0.0
    # def find_convergence_timeseries(self, thpt_list):
    #     if(len(thpt_list)<4):
    #         return 0.
    #     if(len(thpt_list)>15):
    #         return self.find_average_thpt(thpt_list)
    #     tmp = [0]+thpt_list[:-1]
    #     model = AR(tmp)
    #     start_params = [0, 0, 1]
        
    #     model_fit = model.fit(maxlag=1, start_params=start_params, disp=-1)
    #     predicted_last = model_fit.predict(len(tmp), len(tmp))[0]
    #     last_pt = thpt_list[-1]
        
    #     if( (last_pt != 0.) and (predicted_last - last_pt)/last_pt < 0.1):
    #         return predicted_last
    #     return 0.
    # def load_clfs(self):
    #     self.all_clfs = {}
    #     for i in range(3, 16):
    #         self.all_clfs[i] = joblib.load("./clfs/pronghorn-10-%d-42-percentage-optimal.pkl"%i)
    def get_percentage_change_thpts(self, thpt_list):
        if len(thpt_list) <= 1:
            return []
        new_thpt = []
        prev_thpt = thpt_list[0]
        for index in range(1, len(thpt_list)):
            perc = thpt_list[index] - prev_thpt
            new_thpt.append(perc / (prev_thpt+1.5))
            prev_thpt = thpt_list[index]
        return new_thpt
    
#     def find_convergence_dnn(self, thpt_list):
#         threshold = 1.0
# #        print("Actual Thpt list", thpt_list)
# #        print(thpt_list)
#         prev_thpt_list = thpt_list
#         thpt_list = self.get_percentage_change_thpts(thpt_list)
# #        print("Percentage Change thpt", thpt_list)
# #        print(thpt_list)
#         if len(thpt_list)<3:
#             return 0
#         elif len(thpt_list)>=15:
#             return self.find_average_thpt(thpt_list)
#         i = len(thpt_list)
#         y_pred = self.all_clfs[i].predict_proba([thpt_list])[0]
#         max_, ind_ = self.get_max_and_index(y_pred)
#         print("[+] ", max_, threshold - 0.05*(len(thpt_list) - 2), ind_, i, len(prev_thpt_list))
# #        print("CT", ind_, " Prediction probability", max_, " Threshold", threshold - 0.05*(len(thpt_list) - 2))
#         if(max_ > (threshold - 0.05*(len(thpt_list) - 2)) and ind_+2 <= i+1):
#             return self.find_average_thpt(prev_thpt_list)
#         return 0.0
    def get_max_and_index(self, lis):
        max_ = lis[0]
        ind_ = 0
        for i in range(len(lis)):
            if max_ <= lis[i]:
                max_ = lis[i]
                ind_ = i
        return max_, ind_
    def find_average_thpt(self, thpt_list):
        if not thpt_list:
            return 0.
        return (1.0*sum(thpt_list))/len(thpt_list)
    def choose_best_pop(self):
        pass
    def get_random_choices(self, population, weights=None, cum_weights=None, k=1):
        random = rd.random
        if cum_weights is None:
            if weights is None:
                _int = int
                total = len(population)
                return [population[_int(random() * total)] for i in range(k)]
            cum_weights = []
            last_val = 0
            for i in weights:
                last_val += i
                cum_weights.append(last_val)
        elif weights is not None:
            raise TypeError('Cannot specify both weights and cumulative weights')
        if len(cum_weights) != len(population):
            raise ValueError('The number of weights does not match the population')
        bisect = _bisect.bisect
        total = cum_weights[-1]
        hi = len(cum_weights) - 1
        return [population[bisect(cum_weights, random() * total, 0, hi)]
                for i in range(k)]
    def get_average_generation_thpt(self, agents):
        total_score = 0
        for agent in agents:
            total_score += agent.get_score()
        return total_score/len(agents)
#    def get_generation_std(self, agents):
#        scores = []
#        for i in agents:
#            scores.append(i.get_score())
#        return np.array(scores).std()
    def get_generation_std(self, agents):
        n = len(agents)
        if n <= 1:
            return 0.0
        mean, sd = self.get_average_generation_thpt(agents), 0.0
        # calculate stan. dev.
        for el in agents:
            sd += (float(el.get_score()) - mean)**2
        sd = math.sqrt(sd / float(n-1))
        return sd
    
class Agents:
    def __init__(self, population="", population_length=0):
        self.population = population
        self.throughput = -1
        self.average_throughput = 0
        self.memory_error = False
        if population == "":
            self.population = self.get_random_string(population_length)
            
    def get_random_string(self, population_length):
        to_ret = ""
        for i in range(population_length):
            to_ret += rd.choice(["0", "1"])
        return to_ret
    def set_avg_thpt(self, thpt):
        if self.average_throughput:
            self.average_throughput += thpt
            self.average_throughput = self.average_throughput/2.
        else:
            self.average_throughput = thpt
    def get_avg_thpt(self):
        return self.average_throughput
    def set_thpt(self, thpt):
        if self.throughput != -1:
            self.throughput += thpt
            self.throughput /= 2.
        else:
            self.throughput = thpt
    def get_thpt(self):
        return self.throughput
    def get_population(self):
        return self.population
    def get_score(self):
        if self.get_thpt()>0.0:
            return self.get_thpt()
        return self.get_avg_thpt() + self.get_thpt()
    def __lt__ (self, other):
        return self.get_score() < other.get_score()
    def __gt__(self, other):
        return self.get_score() > other.get_score()
    def __eq__(self, other):
        return self.get_score() == other.get_score()

if __name__=="__main__":
    parser = argparse.ArgumentParser(description='Parameters in the application')
    parser.add_argument('sender', type=str,
                    help='Sender information')
    parser.add_argument('receiver', type=str,
                    help='Receiver information')
    parser.add_argument('--interface', type=str,
                    help='Interface to send information in the receiver')
    parser.add_argument('--generation', type=int,
                    help='Number of generation for GA')
    parser.add_argument('--population', type=int,
                    help='Number of population for GA')
    parser.add_argument('--method', type=str,
                    help='Method of algorithm to use')
    parser.add_argument('--conv', type=str,
                    help='Method of algorithm to use')
    parser.add_argument('--manual', type=bool, default=False,
                    help='whether or not to use manual transfers')
    parser.add_argument('--num', type=int,
                    help='value of static parameter')
    args = parser.parse_args()
    print("Argument values:")
    print(args.sender)
    print(args.receiver)
    if(not args.method):
        args.method = "GA"
    if(not args.conv):
        args.method = "avg"
    try:
        ga = GA(args)
    except KeyboardInterrupt:
        exit(0)
    
            
